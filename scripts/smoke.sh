#!/usr/bin/env bash
# Smoke test of the running Compose stack, through nginx only (http://localhost:8080).
#   docker compose up --build -d --wait && scripts/smoke.sh
# Exits non-zero when any check fails; every failed check is printed.
set -euo pipefail

# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

STARTUP_TIMEOUT_SECONDS=60         # UI must answer within this time
REPORT_DEADLINE_SECONDS=10         # success criterion C2: submit -> report with findings
MAX_CONTENT_BYTES=2097152          # 2 MB, the limit of the REST contract
LIST_LIMIT=100
UNKNOWN_FILING_ID="00000000-0000-4000-8000-000000000000"

FAILURES=0

check() {
    local description="$1"
    shift
    if "$@"; then
        ok "$description"
    else
        bad "$description (HTTP $HTTP_STATUS, ${HTTP_CONTENT_TYPE:-no content type}): $(printf '%.300s' "$HTTP_BODY")"
        FAILURES=$(( FAILURES + 1 ))
    fi
}

status_is()        { [ "$HTTP_STATUS" = "$1" ]; }
problem_with()     { status_is "$1" && is_problem_json; }
content_type_is()  { status_is 200 && case "$HTTP_CONTENT_TYPE" in $1) return 0 ;; *) return 1 ;; esac; }

check_ui() {
    log "${BOLD}UI and static files${RESET}"
    http GET "$BASE_URL/"
    check "GET / serves index.html as text/html" content_type_is 'text/html*'
    http GET "$BASE_URL/js/app.js"
    check "GET /js/app.js has a JavaScript MIME type" content_type_is '*/javascript*'
    http GET "$BASE_URL/styles.css"
    check "GET /styles.css is text/css" content_type_is 'text/css*'
    local hidden
    for hidden in mock/server.js mock/ test/ package.json README.md; do
        http GET "$BASE_URL/$hidden"
        check "GET /$hidden is not served (404)" status_is 404
    done
}

check_errors() {
    log "${BOLD}Error responses${RESET}"
    http POST "$BASE_URL/api/filings" -H 'Content-Type: application/json' \
        --data-binary '{"companyName":"","title":"","content":""}'
    check "invalid submit -> 400 application/problem+json" problem_with 400
    http POST "$BASE_URL/api/filings" -H 'Content-Type: application/json' --data-binary '{not json'
    check "malformed JSON -> 400 application/problem+json" problem_with 400
    http GET "$BASE_URL/api/filings/$UNKNOWN_FILING_ID"
    check "unknown filing id -> 404 application/problem+json" problem_with 404
    http GET "$BASE_URL/api/reports/$UNKNOWN_FILING_ID"
    check "unknown report id -> 404 application/problem+json" problem_with 404
}

report_has_findings() {
    local total
    total="$(json_number totalFindings "$HTTP_BODY")"
    [ "$(json_field status "$HTTP_BODY")" = "COMPLETED" ] && [ "${total:-0}" -gt 0 ] \
        && [ "$(count_occurrences '"ruleId"' "$HTTP_BODY")" = "$total" ]
}

check_demo_flow() {
    log "${BOLD}Demo filing end to end (C2: report within ${REPORT_DEADLINE_SECONDS} s)${RESET}"
    local correlation_id started
    correlation_id="smoke-$(new_uuid)"
    started="$(now_ms)"
    CORRELATION_ID="$correlation_id" submit_file "$DEMO_FILING"
    check "submit demo filing -> 202" status_is 202
    check "202 has a Location header" [ -n "$(response_header Location)" ]
    check "X-Correlation-Id passes through nginx and back" \
        [ "$(response_header X-Correlation-Id)" = "$correlation_id" ]
    if [ -z "$FILING_ID" ]; then
        bad "no filing id; skipping the end-to-end checks"
        FAILURES=$(( FAILURES + 1 ))
        return
    fi
    DEMO_FILING_ID="$FILING_ID"
    info "filing $DEMO_FILING_ID"

    wait_for_status_and_report "$DEMO_FILING_ID" "$started"
    if [ -n "$STATUS_MS" ]; then
        ok "status COMPLETED after $STATUS_MS ms"
    else
        bad "status is '$LAST_STATUS', not COMPLETED, ${REPORT_DEADLINE_SECONDS} s after submit"
        explain_stuck_status "$DEMO_FILING_ID"
        FAILURES=$(( FAILURES + 1 ))
    fi
    if [ -n "$REPORT_MS" ]; then
        ok "report available after $REPORT_MS ms (limit ${REPORT_DEADLINE_SECONDS} s)"
        HTTP_BODY="$REPORT_BODY"
        check "report is COMPLETED with findings" report_has_findings
        info "findings: $(json_number totalFindings "$REPORT_BODY"), overall risk: $(json_field overallRiskLevel "$REPORT_BODY")"
    else
        bad "no report within ${REPORT_DEADLINE_SECONDS} s of submit"
        FAILURES=$(( FAILURES + 1 ))
    fi
}

# Polls the filing status and the report together until both are final or the C2 deadline passes.
# Sets STATUS_MS and REPORT_MS (empty when not reached) and REPORT_BODY.
wait_for_status_and_report() {
    local id="$1" started="$2" deadline=$(( $2 + REPORT_DEADLINE_SECONDS * 1000 ))
    STATUS_MS=""; REPORT_MS=""; REPORT_BODY=""; LAST_STATUS=""
    while [ "$(now_ms)" -le "$deadline" ]; do
        if [ -z "$STATUS_MS" ]; then
            LAST_STATUS="$(filing_status "$id")"
            [ "$LAST_STATUS" = "COMPLETED" ] && STATUS_MS="$(elapsed_since "$started")"
        fi
        if [ -z "$REPORT_MS" ]; then
            http GET "$BASE_URL/api/reports/$id"
            if [ "$HTTP_STATUS" = "200" ]; then
                REPORT_MS="$(elapsed_since "$started")"
                REPORT_BODY="$HTTP_BODY"
            fi
        fi
        [ -n "$STATUS_MS" ] && [ -n "$REPORT_MS" ] && return 0
        sleep "$POLL_INTERVAL_SECONDS"
    done
}

check_list() {
    log "${BOLD}Filing list${RESET}"
    http GET "$BASE_URL/api/filings?limit=$LIST_LIMIT"
    check "GET /api/filings -> 200" status_is 200
    if [ -n "${DEMO_FILING_ID:-}" ]; then
        check "list contains the demo filing" [ "$(count_occurrences "$DEMO_FILING_ID" "$HTTP_BODY")" -ge 1 ]
    fi
}

# write_filing FILE CONTENT_BYTES: a valid request whose content has exactly CONTENT_BYTES ASCII bytes.
write_filing() {
    {
        printf '{"companyName":"Smoke Size Test Inc.","title":"Size test %s bytes","content":"' "$2"
        head -c "$2" /dev/zero | tr '\0' 'a'
        printf '"}'
    } > "$1"
}

check_size_limit() {
    log "${BOLD}Size limit through nginx${RESET}"
    local at_limit="$WORK_DIR/at-limit.json" over_limit="$WORK_DIR/over-limit.json"
    write_filing "$at_limit" "$MAX_CONTENT_BYTES"
    write_filing "$over_limit" $(( MAX_CONTENT_BYTES + 1 ))
    submit_file "$at_limit"
    check "content of exactly 2 MB ($MAX_CONTENT_BYTES bytes) -> 202" status_is 202
    submit_file "$over_limit"
    check "content of 2 MB + 1 byte -> 400 application/problem+json (not 413)" problem_with 400
}

main() {
    require_tools curl
    log "${BOLD}VeriTrade smoke test against $BASE_URL${RESET}"
    wait_for_ui "$STARTUP_TIMEOUT_SECONDS" || die "the UI at $BASE_URL did not answer within ${STARTUP_TIMEOUT_SECONDS} s"
    check_ui
    check_errors
    check_demo_flow
    check_list
    check_size_limit
    if [ "$FAILURES" -gt 0 ]; then
        die "smoke test failed: $FAILURES check(s) failed"
    fi
    log "${GREEN}${BOLD}Smoke test passed${RESET}"
}

main "$@"
