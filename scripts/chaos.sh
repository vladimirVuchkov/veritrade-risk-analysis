#!/usr/bin/env bash
# End-to-end resilience scenarios against the running Compose stack (success criterion C3).
#   docker compose up --build -d --wait && scripts/chaos.sh [a b c d e f]
# Uses only docker compose and curl (REST through nginx, RabbitMQ through the management API).
# Each scenario prints PASS or FAIL; the script exits non-zero when any scenario fails.
set -euo pipefail

# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

HOLD_SECONDS=5                 # how long a filing must stay untouched while its consumer is down
PROCESSING_TIMEOUT_SECONDS=30  # submit (or recovery) -> COMPLETED / report 200
DEAD_LETTER_TIMEOUT_SECONDS=15 # publish -> message visible in a dead-letter queue
LOG_TIMEOUT_SECONDS=15         # publish -> the consumer logs that it handled the event
SERVICE_START_TIMEOUT_SECONDS=120
UPSTREAM_UNAVAILABLE_MAX_MS=5000 # nginx: stopped upstream -> 503 (proxy_connect_timeout is 2 s)
UPSTREAM_ADDRESS_CACHE_SECONDS=7 # nginx keeps a resolved address 5 s (resolver valid=5s), plus a margin

SCENARIOS_ALL="a b c d e f"
RESULTS=""
FAILED=0

stop_service() {
    info "stopping $1"
    compose stop "$1" > /dev/null 2>&1
}

start_service() {
    info "starting $1 and waiting until it is healthy"
    compose up -d --no-deps --no-build --wait --wait-timeout "$SERVICE_START_TIMEOUT_SECONDS" "$1" > /dev/null 2>&1
}

restart_service() {
    info "restarting $1"
    compose restart "$1" > /dev/null 2>&1
    compose up -d --no-deps --no-build --wait --wait-timeout "$SERVICE_START_TIMEOUT_SECONDS" "$1" > /dev/null 2>&1
}

ensure_stack_up() {
    compose up -d --no-build --wait --wait-timeout "$SERVICE_START_TIMEOUT_SECONDS" > /dev/null 2>&1
}

service_logs_contain() {
    compose logs --no-color "$1" 2> /dev/null | grep -F "$2" | grep -q -F "${3:-$2}"
}

# wait_for_log SERVICE TEXT [SECOND_TEXT]: a log line containing both texts appears.
wait_for_log() {
    local deadline=$(( $(now_ms) + LOG_TIMEOUT_SECONDS * 1000 ))
    while [ "$(now_ms)" -lt "$deadline" ]; do
        service_logs_contain "$@" && return 0
        sleep 1
    done
    return 1
}

fail() {
    bad "$*"
    return 1
}

submit_demo() {
    CORRELATION_ID="chaos-$1-$(new_uuid)" submit_file "$DEMO_FILING"
    [ -n "$FILING_ID" ] || fail "submit returned HTTP $HTTP_STATUS: $HTTP_BODY" || return 1
    info "submitted filing $FILING_ID"
}

expect_status_now() {
    local actual
    actual="$(filing_status "$1")"
    [ "$actual" = "$2" ] || fail "filing $1 is $actual, expected $2" || return 1
    ok "filing is $2"
}

expect_completed() {
    if ! wait_for_status "$1" COMPLETED "$PROCESSING_TIMEOUT_SECONDS"; then
        bad "filing $1 is '$LAST_STATUS', not COMPLETED within ${PROCESSING_TIMEOUT_SECONDS} s"
        explain_stuck_status "$1"
        return 1
    fi
    ok "filing reached COMPLETED"
}


expect_report() {
    wait_for_report "$1" 200 "$PROCESSING_TIMEOUT_SECONDS" \
        || fail "report of $1 is HTTP $HTTP_STATUS, not 200 within ${PROCESSING_TIMEOUT_SECONDS} s" || return 1
    [ "$(json_number totalFindings "$HTTP_BODY")" -gt 0 ] || fail "report has no findings" || return 1
    ok "report is available with $(json_number totalFindings "$HTTP_BODY") findings"
}

# analysis.completed for FILING_ID with one finding that the real rules never produce.
completed_event() {
    local event_id="$1" filing_id="$2" now
    now="$(utc_now)"
    printf '{"eventId":"%s","eventType":"ANALYSIS_COMPLETED","eventVersion":1,"occurredAt":"%s","correlationId":"%s",' \
        "$event_id" "$now" "$event_id"
    printf '"payload":{"filingId":"%s","analyzedAt":"%s","rulesVersion":"chaos","summary":{"totalFindings":1,' \
        "$filing_id" "$now"
    printf '"overallRiskLevel":"LOW","byCategory":{"MARKET":1}},"findings":[{"category":"MARKET","severity":"LOW",'
    printf '"ruleId":"CHAOS-001","matchedText":"duplicate","excerpt":"duplicate delivery test","position":0}]}}'
}

started_event() {
    local now
    now="$(utc_now)"
    printf '{"eventId":"%s","eventType":"ANALYSIS_STARTED","eventVersion":1,"occurredAt":"%s","correlationId":"%s",' \
        "$1" "$now" "$1"
    printf '"payload":{"filingId":"%s","startedAt":"%s"}}' "$2" "$now"
}

# (a) A stopped Analysis loses nothing: the filing waits in its queue and is processed after the restart.
scenario_a() {
    stop_service analysis-service
    submit_demo a || return 1
    sleep "$HOLD_SECONDS"
    expect_status_now "$FILING_ID" SUBMITTED || return 1
    http GET "$BASE_URL/api/reports/$FILING_ID"
    [ "$HTTP_STATUS" = "404" ] || fail "report is HTTP $HTTP_STATUS while Analysis is down, expected 404" || return 1
    ok "report is 404 while Analysis is down"
    start_service analysis-service || fail "analysis-service did not become healthy" || return 1
    expect_completed "$FILING_ID" || return 1
    expect_report "$FILING_ID"
}

# probe_loop URL FLAG_FILE OUT_FILE: one request every POLL_INTERVAL_SECONDS while FLAG_FILE exists.
# Each line: start time (ms), HTTP status (000 when there was no answer), total time (s), content type.
probe_loop() {
    local started
    while [ -f "$2" ]; do
        started="$(now_ms)"
        curl -s -o /dev/null --max-time "$HTTP_TIMEOUT_SECONDS" \
            -w "$started %{http_code} %{time_total} %{content_type}\n" "$1" >> "$3" || true
        sleep "$POLL_INTERVAL_SECONDS"
    done
}

# stop_with_fast_unavailable SERVICE PATH: probes PATH in the background while SERVICE stops and until
# nginx's cached address of the old container has expired. Every answer (also those sent while nginx
# still holds that address) must arrive within UPSTREAM_UNAVAILABLE_MAX_MS, and every request sent
# after the stop must be a 503 problem+json. One request each, no retry.
stop_with_fast_unavailable() {
    local flag="$WORK_DIR/probing" probes="$WORK_DIR/probes" prober stopped_at summary
    : > "$probes"
    touch "$flag"
    probe_loop "$BASE_URL$2" "$flag" "$probes" &
    prober=$!
    stop_service "$1"
    stopped_at="$(now_ms)"
    sleep "$UPSTREAM_ADDRESS_CACHE_SECONDS"
    rm -f "$flag"
    wait "$prober" || true
    # slowest-ms failed-requests answers-after-stop non-503-after-stop
    summary="$(awk -v stopped="$stopped_at" '
        { ms = $3 * 1000; if (ms > max) max = ms; if ($2 == "000") failed++ }
        $1 >= stopped { after++; if ($2 != "503" || $4 !~ /problem\+json/) wrong++ }
        END { printf "%d %d %d %d", max, failed, after, wrong }' "$probes")"
    set -- $summary
    info "$(wc -l < "$probes" | tr -d ' ') requests across the stop, slowest ${1} ms, ${3} after the stop"
    [ "$2" -eq 0 ] || fail "$2 request(s) got no answer: $(grep ' 000 ' "$probes" | head -n 3)" || return 1
    [ "$1" -le "$UPSTREAM_UNAVAILABLE_MAX_MS" ] \
        || fail "the slowest answer took $1 ms, limit $UPSTREAM_UNAVAILABLE_MAX_MS ms" || return 1
    [ "$3" -gt 0 ] && [ "$4" -eq 0 ] \
        || fail "$4 of $3 answers after the stop were not 503 problem+json" || return 1
    ok "every answer across the stop within $UPSTREAM_UNAVAILABLE_MAX_MS ms, 503 problem+json after it"
}

# (b) A stopped Reporting loses nothing: the result waits in its queue; the report appears after the restart.
#     A stopped upstream cannot answer 404; nginx answers 503 problem+json at once, also while it
#     still holds the old container address.
scenario_b() {
    stop_with_fast_unavailable reporting-service "/api/reports/$(new_uuid)" || return 1
    submit_demo b || return 1
    expect_completed "$FILING_ID" || return 1
    http GET "$BASE_URL/api/reports/$FILING_ID"
    [ "$HTTP_STATUS" = "503" ] && is_problem_json \
        || fail "report is HTTP $HTTP_STATUS ($HTTP_CONTENT_TYPE) while Reporting is down, expected 503 problem+json" \
        || return 1
    ok "report is 503 problem+json while Reporting is down"
    start_service reporting-service || fail "reporting-service did not become healthy" || return 1
    expect_report "$FILING_ID"
}

# (c) The same analysis.completed delivered twice gives one consistent report; the real, later
#     analysis result of the same filing is ignored (first terminal event wins).
scenario_c() {
    local event_id event report_before
    event_id="$(new_uuid)"
    stop_service analysis-service
    submit_demo c || return 1
    event="$(completed_event "$event_id" "$FILING_ID")"
    rabbit_publish analysis.completed "$event" "$event_id" || fail "first publish not routed: $HTTP_BODY" || return 1
    rabbit_publish analysis.completed "$event" "$event_id" || fail "second publish not routed: $HTTP_BODY" || return 1
    info "published analysis.completed $event_id twice"
    wait_for_log reporting-service "Duplicate event ignored: eventId=$event_id" \
        || fail "Reporting did not log the second delivery of $event_id as a duplicate" || return 1
    ok "Reporting ignored the second delivery as a duplicate"
    expect_report_from_duplicate "$FILING_ID" || return 1
    report_before="$HTTP_BODY"
    expect_completed "$FILING_ID" || return 1

    start_service analysis-service || fail "analysis-service did not become healthy" || return 1
    wait_for_log analysis-service "Filing $FILING_ID analysed" \
        || fail "Analysis did not analyse the queued filing" || return 1
    wait_for_log reporting-service "Late or contradictory event ignored" "$FILING_ID" \
        || fail "Reporting did not log the real (later) result as ignored" || return 1
    http GET "$BASE_URL/api/reports/$FILING_ID"
    [ "$HTTP_BODY" = "$report_before" ] || fail "report changed after the real analysis: $HTTP_BODY" || return 1
    ok "report is unchanged after the real analysis result arrived"
    expect_status_now "$FILING_ID" COMPLETED || return 1
    ! queue_contains reporting.analysis-results.dlq "$FILING_ID" \
        || fail "a result of $FILING_ID was dead-lettered by Reporting" || return 1
    ok "nothing of this filing in reporting.analysis-results.dlq"
}

expect_report_from_duplicate() {
    wait_for_report "$1" 200 "$PROCESSING_TIMEOUT_SECONDS" || fail "no report for $1" || return 1
    [ "$(json_number totalFindings "$HTTP_BODY")" = "1" ] \
        && [ "$(count_occurrences '"ruleId":"CHAOS-001"' "$HTTP_BODY")" = "1" ] \
        || fail "expected exactly one finding CHAOS-001, got: $HTTP_BODY" || return 1
    ok "exactly one report with one finding"
}

# (d) A poison message on filing.submitted goes straight to analysis.filing-submitted.dlq.
scenario_d() {
    local token
    token="chaos-poison-$(new_uuid)"
    rabbit_publish filing.submitted "this is not JSON $token" "$token" || fail "publish not routed: $HTTP_BODY" || return 1
    info "published an invalid body to filing.submitted ($token)"
    wait_for_queue_to_contain analysis.filing-submitted.dlq "$token" "$DEAD_LETTER_TIMEOUT_SECONDS" \
        || fail "poison message not in analysis.filing-submitted.dlq within ${DEAD_LETTER_TIMEOUT_SECONDS} s" || return 1
    ok "poison message is in analysis.filing-submitted.dlq"
}

# (e) Ingestion restarted right after a submit still delivers the filing (outbox survives the restart).
scenario_e() {
    submit_demo e || return 1
    restart_service ingestion-service || fail "ingestion-service did not become healthy" || return 1
    expect_completed "$FILING_ID" || return 1
    expect_report "$FILING_ID"
}

# (f) A late analysis.started after COMPLETED is acknowledged and ignored, not dead-lettered.
scenario_f() {
    local event_id
    event_id="$(new_uuid)"
    submit_demo f || return 1
    expect_completed "$FILING_ID" || return 1
    rabbit_publish analysis.started "$(started_event "$event_id" "$FILING_ID")" "$event_id" \
        || fail "publish not routed: $HTTP_BODY" || return 1
    info "published a late analysis.started $event_id"
    wait_for_log ingestion-service "Late or contradictory event $event_id ignored" \
        || fail "Ingestion did not log the late event $event_id as ignored" || return 1
    ok "Ingestion logged the late event as ignored"
    expect_status_now "$FILING_ID" COMPLETED || return 1
    ! queue_contains ingestion.analysis-events.dlq "$event_id" \
        || fail "late event $event_id was dead-lettered" || return 1
    ok "late event is not in ingestion.analysis-events.dlq"
}

describe() {
    case "$1" in
        a) echo "stopped Analysis: filing waits, then completes" ;;
        b) echo "stopped Reporting: report appears after restart" ;;
        c) echo "duplicate analysis.completed: one consistent report" ;;
        d) echo "poison filing.submitted goes to the DLQ" ;;
        e) echo "Ingestion restart right after submit: no outbox loss" ;;
        f) echo "late analysis.started after COMPLETED is ignored" ;;
        *) die "unknown scenario '$1' (use: $SCENARIOS_ALL)" ;;
    esac
}

run_scenario() {
    local name="$1" title started result elapsed
    title="$(describe "$name")"
    log ""
    log "${BOLD}($name) $title${RESET}"
    started="$(now_ms)"
    if "scenario_$name"; then result="PASS"; else result="FAIL"; FAILED=$(( FAILED + 1 )); fi
    elapsed="$(elapsed_since "$started")"
    ensure_stack_up || bad "could not bring the stack back up after scenario $name"
    if [ "$result" = "PASS" ]; then
        log "  ${GREEN}PASS${RESET} ($name) in ${elapsed} ms"
    else
        log "  ${RED}FAIL${RESET} ($name) in ${elapsed} ms"
    fi
    RESULTS="$RESULTS
  $result ($name) $title - ${elapsed} ms"
}

main() {
    require_tools curl docker
    warn_if_weak_credentials
    local scenarios="${*:-$SCENARIOS_ALL}" name
    log "${BOLD}VeriTrade chaos test against $BASE_URL and $RABBIT_API${RESET}"
    ensure_stack_up || die "the Compose stack is not healthy; start it with: docker compose up --build -d --wait"
    wait_for_ui "$SERVICE_START_TIMEOUT_SECONDS" || die "the UI at $BASE_URL does not answer"
    for name in $scenarios; do
        describe "$name" > /dev/null
        run_scenario "$name"
    done
    log ""
    log "${BOLD}Summary${RESET}$RESULTS"
    [ "$FAILED" -eq 0 ] || die "$FAILED scenario(s) failed"
    log "${GREEN}${BOLD}All chaos scenarios passed${RESET}"
}

main "$@"
