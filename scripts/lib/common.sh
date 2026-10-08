# Shared helpers for smoke.sh and chaos.sh. Source it; do not run it.
# Works with bash 3.2 (macOS) and needs only curl and docker compose. No jq: the JSON answers of the
# services are single-line, so small sed/grep extractions are enough.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [ -f "$ROOT_DIR/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    . "$ROOT_DIR/.env"
    set +a
fi

BASE_URL="${BASE_URL:-http://localhost:${UI_PORT:-8080}}"
RABBIT_API="${RABBIT_API:-http://localhost:${RABBITMQ_MANAGEMENT_PORT:-15672}/api}"
RABBIT_USER="${RABBITMQ_USERNAME:-veritrade}"
RABBIT_PASS="${RABBITMQ_PASSWORD:-veritrade}"
RABBIT_VHOST="%2F"

HTTP_TIMEOUT_SECONDS=15
POLL_INTERVAL_SECONDS=0.2
DEMO_FILING="$ROOT_DIR/scripts/demo-filing.json"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

if [ -t 1 ]; then
    GREEN=$'\033[32m'; RED=$'\033[31m'; BOLD=$'\033[1m'; RESET=$'\033[0m'
else
    GREEN=""; RED=""; BOLD=""; RESET=""
fi

log()  { printf '%s\n' "$*"; }
info() { printf '  - %s\n' "$*"; }
ok()   { printf '  %sok%s   %s\n' "$GREEN" "$RESET" "$*"; }
bad()  { printf '  %sFAIL%s %s\n' "$RED" "$RESET" "$*" >&2; }
die()  { printf '%sERROR%s %s\n' "$RED" "$RESET" "$*" >&2; exit 1; }

require_tools() {
    local tool
    for tool in "$@"; do
        command -v "$tool" > /dev/null 2>&1 || die "'$tool' is required but not installed"
    done
}

# Milliseconds since the epoch, for timings only. bash 5 has EPOCHREALTIME and GNU date has %N;
# macOS ships bash 3.2 and BSD date, so there perl (always present) gives the milliseconds.
now_ms() {
    if [ -n "${EPOCHREALTIME:-}" ]; then
        local micros="${EPOCHREALTIME/[.,]/}"
        echo $(( micros / 1000 ))
        return
    fi
    local stamp
    stamp="$(date +%s%3N)"
    case "$stamp" in
        *N) ;;
        *)  echo "$stamp"; return ;;
    esac
    if command -v perl > /dev/null 2>&1; then
        perl -MTime::HiRes=time -e 'printf "%d\n", time * 1000'
    else
        echo $(( $(date +%s) * 1000 ))
    fi
}

new_uuid() {
    if command -v uuidgen > /dev/null 2>&1; then
        uuidgen | tr '[:upper:]' '[:lower:]'
    else
        cat /proc/sys/kernel/random/uuid
    fi
}

utc_now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# http METHOD URL [curl args...]
# Sets HTTP_STATUS, HTTP_CONTENT_TYPE and HTTP_BODY. A connection failure gives HTTP_STATUS=000.
http() {
    local method="$1" url="$2"
    shift 2
    local meta
    : > "$WORK_DIR/body"
    : > "$WORK_DIR/headers"
    if ! meta="$(curl -sS --max-time "$HTTP_TIMEOUT_SECONDS" -X "$method" \
            -o "$WORK_DIR/body" -D "$WORK_DIR/headers" \
            -w '%{http_code} %{content_type}' "$@" "$url" 2> "$WORK_DIR/curl-error")"; then
        HTTP_STATUS="000"
        HTTP_CONTENT_TYPE=""
        HTTP_BODY="$(cat "$WORK_DIR/curl-error")"
        return 0
    fi
    HTTP_STATUS="${meta%% *}"
    HTTP_CONTENT_TYPE="${meta#* }"
    HTTP_BODY="$(cat "$WORK_DIR/body")"
}

# Value of a response header of the last http call (case-insensitive name), without the trailing CR.
response_header() {
    grep -i "^$1:" "$WORK_DIR/headers" | tail -n 1 | cut -d: -f2- | sed 's/^ *//' | tr -d '\r'
}

# String value of the first "name":"value" pair in a JSON text.
json_field() {
    printf '%s' "$2" | grep -o "\"$1\":\"[^\"]*\"" | head -n 1 | sed "s/^\"$1\":\"//; s/\"\$//"
}

json_number() {
    printf '%s' "$2" | grep -o "\"$1\":[0-9][0-9]*" | head -n 1 | sed "s/^\"$1\"://"
}

count_occurrences() {
    printf '%s' "$2" | grep -o -F "$1" | wc -l | tr -d ' '
}

is_problem_json() {
    case "$HTTP_CONTENT_TYPE" in
        application/problem+json*) return 0 ;;
        *) return 1 ;;
    esac
}

elapsed_since() {
    echo $(( $(now_ms) - $1 ))
}

# --- REST API --------------------------------------------------------------------------------

# submit_file JSON_FILE [title-suffix] -> sets FILING_ID (empty on failure) and leaves HTTP_* set.
submit_file() {
    http POST "$BASE_URL/api/filings" -H 'Content-Type: application/json' \
        -H "X-Correlation-Id: ${CORRELATION_ID:-$(new_uuid)}" --data-binary "@$1"
    FILING_ID=""
    if [ "$HTTP_STATUS" = "202" ]; then
        FILING_ID="$(json_field filingId "$HTTP_BODY")"
    fi
}

filing_status() {
    http GET "$BASE_URL/api/filings/$1"
    if [ "$HTTP_STATUS" = "200" ]; then
        json_field status "$HTTP_BODY"
    else
        echo "HTTP_$HTTP_STATUS"
    fi
}

# wait_for_status FILING_ID EXPECTED TIMEOUT_SECONDS -> 0 when reached; LAST_STATUS holds the last value.
wait_for_status() {
    local id="$1" expected="$2" deadline=$(( $(now_ms) + $3 * 1000 ))
    LAST_STATUS=""
    while [ "$(now_ms)" -lt "$deadline" ]; do
        LAST_STATUS="$(filing_status "$id")"
        [ "$LAST_STATUS" = "$expected" ] && return 0
        case "$LAST_STATUS" in
            COMPLETED|FAILED) return 1 ;;
        esac
        sleep "$POLL_INTERVAL_SECONDS"
    done
    return 1
}

# wait_for_report FILING_ID EXPECTED_HTTP TIMEOUT_SECONDS -> 0 when the report answers EXPECTED_HTTP.
wait_for_report() {
    local id="$1" expected="$2" deadline=$(( $(now_ms) + $3 * 1000 ))
    while [ "$(now_ms)" -lt "$deadline" ]; do
        http GET "$BASE_URL/api/reports/$id"
        [ "$HTTP_STATUS" = "$expected" ] && return 0
        sleep "$POLL_INTERVAL_SECONDS"
    done
    return 1
}

wait_for_ui() {
    local deadline=$(( $(now_ms) + $1 * 1000 ))
    while [ "$(now_ms)" -lt "$deadline" ]; do
        http GET "$BASE_URL/"
        [ "$HTTP_STATUS" = "200" ] && return 0
        sleep 1
    done
    return 1
}

# --- RabbitMQ management API -------------------------------------------------------------------

rabbit() {
    local method="$1" path="$2"
    shift 2
    http "$method" "$RABBIT_API$path" -u "$RABBIT_USER:$RABBIT_PASS" "$@"
}

json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

# rabbit_publish ROUTING_KEY BODY MESSAGE_ID -> 0 when the broker routed the message to a queue.
rabbit_publish() {
    local request
    request="{\"routing_key\":\"$1\",\"payload\":\"$(json_escape "$2")\",\"payload_encoding\":\"string\",\
\"properties\":{\"message_id\":\"$3\",\"correlation_id\":\"$3\",\"content_type\":\"application/json\",\"delivery_mode\":2}}"
    rabbit POST "/exchanges/$RABBIT_VHOST/veritrade.events/publish" \
        -H 'Content-Type: application/json' --data-binary "$request"
    [ "$HTTP_STATUS" = "200" ] && [ "$(count_occurrences '"routed":true' "$HTTP_BODY")" = "1" ]
}

# Messages of a queue, peeked and requeued (only used on dead-letter queues, which have no consumer).
peek_queue() {
    rabbit POST "/queues/$RABBIT_VHOST/$1/get" -H 'Content-Type: application/json' \
        --data-binary '{"count":1000,"ackmode":"ack_requeue_true","encoding":"auto","truncate":100000}'
    printf '%s' "$HTTP_BODY"
}

queue_contains() {
    peek_queue "$1" | grep -q -F "$2"
}

wait_for_queue_to_contain() {
    local queue="$1" token="$2" deadline=$(( $(now_ms) + $3 * 1000 ))
    while [ "$(now_ms)" -lt "$deadline" ]; do
        queue_contains "$queue" "$token" && return 0
        sleep "$POLL_INTERVAL_SECONDS"
    done
    return 1
}

# Diagnoses a filing stuck before COMPLETED: were its analysis events dead-lettered by Ingestion?
explain_stuck_status() {
    if queue_contains ingestion.analysis-events.dlq "$1"; then
        bad "analysis events of $1 are in ingestion.analysis-events.dlq: Ingestion rejected them"
    fi
}
