# Manual testing

Step-by-step checks of the running system by hand: the UI, the API, a `FAILED` filing, the
dead-letter queues and the behaviour when a service is down. The automated versions of these checks
are `scripts/smoke.sh`, `scripts/chaos.sh` and the end-to-end suite (see the README,
[How to run the tests](../README.md#how-to-run-the-tests)).

Needs Docker with Compose v2, `curl` and a browser. All commands run from the repository root.

## 0. Start and stop

```bash
docker compose up --build -d --wait     # about 2-3 minutes on the first build
docker compose ps                        # five containers, all "healthy"
```

| What | Address |
|---|---|
| UI | http://127.0.0.1:8080 |
| RabbitMQ management | http://127.0.0.1:15672, user `veritrade`, password `veritrade` (or the values in `.env`) |

When you are done: `docker compose down -v` (removes the H2 files and the broker data too).

The commands below use these two shell variables:

```bash
API=http://127.0.0.1:8080
RABBIT=http://127.0.0.1:15672/api
RABBIT_AUTH=veritrade:veritrade
```

## 1. Happy path in the UI

1. Open http://127.0.0.1:8080.
2. Click **Load sample**, then **Submit**.
3. Expected: the status moves `SUBMITTED -> ANALYZING -> COMPLETED` within a few seconds, then the
   report appears: overall risk level, findings by category and severity, excerpts with the matched
   phrase highlighted.
4. The filing is listed under the recent filings; clicking it opens the same report again.
5. Upload [`samples/sample-10k-excerpt.txt`](../samples/sample-10k-excerpt.txt) as a `.txt` file and
   submit it: the report has more findings, in several categories.
6. Submit a text with no risk phrases (for example `The company had a quiet year.`): `COMPLETED`, risk
   level `NONE`, no findings.
7. Narrow the browser window to about 390 px: the form, the list and the report stay readable.

## 2. Happy path through the API

```bash
curl -i -X POST $API/api/filings -H 'Content-Type: application/json' \
  -d '{"companyName":"Acme Holdings Inc.","title":"Form 10-K 2025","content":"We are subject to pending litigation. Management identified a material weakness in internal control."}'
```

Expected: `202 Accepted`, a `Location: /api/filings/{filingId}` header, an `X-Correlation-Id`
header and the body `{"filingId":"...","status":"SUBMITTED"}`. Then:

```bash
ID=<filingId from the answer>
curl -s $API/api/filings/$ID            # "status":"COMPLETED" after a second or two
curl -s $API/api/reports/$ID            # 200 with findings (404 problem until the report is stored)
curl -s "$API/api/filings?limit=5"      # the filing is first in the list
```

## 3. Validation and errors

Each answer is an RFC 9457 problem (`Content-Type: application/problem+json`):

| Request | Expected |
|---|---|
| `curl -i -X POST $API/api/filings -H 'Content-Type: application/json' -d '{"companyName":"","title":"t","content":"x"}'` | 400, the field is named in the problem |
| `curl -i -X POST $API/api/filings -H 'Content-Type: application/json' -d '{not json'` | 400 |
| `curl -i $API/api/filings/00000000-0000-0000-0000-000000000000` | 404 |
| `curl -i $API/api/reports/not-a-uuid` | 404, never a 500 |
| `curl -i "$API/api/filings?limit=0"` | 400 |

In the UI, an empty company name or a file over 2 MB is rejected before anything is sent.

## 4. A `FAILED` filing in the UI (real stack)

The real Analysis fails only on an internal error, so this step makes one by hand: Analysis is
stopped, so the filing waits, and an `analysis.failed` event for it is published through the
RabbitMQ management API. When Analysis starts again, its real result arrives late and is ignored,
because the first terminal event wins.

```bash
docker compose stop analysis-service
```

1. In the UI, submit the sample. The status stays `SUBMITTED` (the UI keeps polling for about a
   minute; if it gives up, click the filing in the recent list later to follow it again).
2. Copy the filing id from the list or from `curl -s "$API/api/filings?limit=1"`, then publish the
   failure:

```bash
ID=<filingId>
EVENT_ID=$(uuidgen | tr 'A-Z' 'a-z')
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
BODY="{\"eventId\":\"$EVENT_ID\",\"eventType\":\"ANALYSIS_FAILED\",\"eventVersion\":1,\"occurredAt\":\"$NOW\",\"correlationId\":\"manual-test\",\"payload\":{\"filingId\":\"$ID\",\"failedAt\":\"$NOW\",\"reason\":\"Manual test: rule engine error\"}}"
curl -s -u $RABBIT_AUTH -H 'Content-Type: application/json' -X POST $RABBIT/exchanges/%2F/veritrade.events/publish \
  -d "{\"routing_key\":\"analysis.failed\",\"payload\":$(printf '%s' "$BODY" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))'),\"payload_encoding\":\"string\",\"properties\":{\"message_id\":\"$EVENT_ID\",\"correlation_id\":\"manual-test\",\"content_type\":\"application/json\",\"delivery_mode\":2}}"
# {"routed":true}
```

3. Expected in the UI: the status becomes `FAILED` and the reason `Manual test: rule engine error`
   is shown. `curl -s $API/api/reports/$ID` returns a report with status `FAILED` and the same
   reason.
4. Start Analysis again:

```bash
docker compose start analysis-service
docker compose logs ingestion-service reporting-service | grep -i late
```

   Expected: Ingestion and Reporting log the real `analysis.started` / `analysis.completed` as late
   events; the filing stays `FAILED`, and no dead-letter queue gets a new message (step 6 shows how
   to look).

The same can be done by hand in the management UI: **Exchanges -> `veritrade.events` -> Publish
message**, routing key `analysis.failed`, property `content_type = application/json`, the JSON
`BODY` above as payload. The mock server of the frontend shows a `FAILED` filing without the backend:
`node frontend/mock/server.js`, and put `[fail]` in the title.

## 5. A service is down

| Step | Expected |
|---|---|
| `docker compose stop analysis-service`, submit in the UI | stays `SUBMITTED`; in the management UI the queue `analysis.filing-submitted` holds 1 message |
| `docker compose start analysis-service` | the filing reaches `COMPLETED` and the report appears; nothing was lost |
| `docker compose stop reporting-service`, submit | status `COMPLETED`, but the report answers 503 problem+json quickly; the UI says the report is not available yet |
| `docker compose start reporting-service` | the report appears (the result waited in `reporting.analysis-results`) |
| `docker compose restart ingestion-service` right after a submit | the filing still reaches `COMPLETED` (the outbox survives the restart) |
| `docker compose stop rabbitmq`, submit, wait 10 s, `docker compose start rabbitmq` | the submit is still accepted (202); within about half a minute after the broker is back, the outbox sends the event and the filing completes |

## 6. Poison message and dead-letter queues

```bash
curl -s -u $RABBIT_AUTH -H 'Content-Type: application/json' -X POST $RABBIT/exchanges/%2F/veritrade.events/publish \
  -d '{"routing_key":"filing.submitted","payload":"this is not JSON","payload_encoding":"string","properties":{"content_type":"application/json","delivery_mode":2}}'
curl -s -u $RABBIT_AUTH $RABBIT/queues/%2F/analysis.filing-submitted.dlq | grep -o '"messages":[0-9]*'
```

Expected: `{"routed":true}`, then one message more than before (the management API refreshes its
counts every few seconds, so wait a moment before the second command): the message went to the
dead-letter queue at once, without retries, and Analysis keeps working; submit another filing in the
UI and it completes. In the management UI: **Queues -> `analysis.filing-submitted.dlq` -> Get
messages** shows the body. The other two dead-letter queues are `ingestion.analysis-events.dlq` and
`reporting.analysis-results.dlq`.

## 7. Correlation id

```bash
curl -s -o /dev/null -D - -X POST $API/api/filings -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: manual-check-42' \
  -d '{"companyName":"Acme","title":"Correlation","content":"pending litigation"}' | grep -i correlation
docker compose logs | grep manual-check-42
```

Expected: the header comes back unchanged, and the id appears in the logs of nginx and of all three
services. An invalid id (for example with a space) is replaced with a generated UUID.

## 8. Clean up

```bash
docker compose down -v
```
