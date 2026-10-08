# Handoff - Agent E (Infrastructure)

## Done
- **E1 Dockerfiles.** I kept the single parameterized `infra/docker/java-service.Dockerfile`
  (`SERVICE`, `PORT` build args) instead of one file per service. The three builds are identical
  except for the module name and port, so three copies would only drift apart. Compose passes the args.
  The build:
  - is multi-stage with the repository root as context;
  - runs `./mvnw -B -q -pl <svc> -am package -DskipTests` with a BuildKit cache mount on `/root/.m2`.
    Only `src/main` is copied, so no test code is compiled in the image;
  - extracts the Spring Boot layered jar (`-Djarmode=tools extract --layers --launcher`) onto
    `eclipse-temurin:21-jre-alpine`;
  - runs as the non-root user `app` (uid 10001), which owns `/app/data`;
  - sets `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`;
  - has a `HEALTHCHECK` with busybox `wget -qO- http://localhost:$PORT/actuator/health`.
- **E2 RabbitMQ.** Uses `rabbitmq:4.3-management-alpine` with the management UI on 15672 and a
  `rabbitmq-diagnostics check_port_connectivity` health check. There is no definitions file; the
  services declare the topology.
- **E3 nginx.** `infra/docker/frontend.Dockerfile` builds on `nginxinc/nginx-unprivileged:1.29-alpine`,
  which runs as non-root on port 8080. `infra/nginx/default.conf` does the following:
  - The image holds only `index.html`, `styles.css` and `js/`. The Dockerfile-specific ignore file
    `frontend.Dockerfile.dockerignore` replaces the root `.dockerignore` for this build, so `mock/`,
    `test/`, `package.json` and `README.md` never reach the builder.
  - `.js` is served as `application/javascript` (the stock `mime.types`).
  - `/api/filings` is proxied to `ingestion-service:8081` and `/api/reports` to `reporting-service:8083`,
    with the full URI (`/api` is not stripped). Other `/api/*` paths return a 404 problem+json.
  - The upstreams are resolved per request through Docker DNS (`resolver 127.0.0.11`, variables in
    `proxy_pass`). nginx therefore follows a restarted container and starts even when a service is down.
  - `client_max_body_size 3m`, so a 2 MB + 1 byte filing reaches Ingestion and gets its 400.
  - `X-Correlation-Id` is passed to the service and back to the client, and it is written in the access log.
  - A stopped upstream gives `503 application/problem+json` (not an HTML 502 page). A body over 3 MB
    gives `413 application/problem+json`.
  - `/healthz` is used for the container health check. All responses carry `X-Content-Type-Options: nosniff`.
- **E4 Compose.**
  - Every container has a health check. Java services use `start_interval: 2s`, so `--wait` returns
    quickly.
  - Dependencies use `depends_on: condition: service_healthy`: Java services wait for RabbitMQ, and
    the frontend waits for Ingestion and Reporting.
  - Named volumes `ingestion-data` and `reporting-data` hold the H2 files; `rabbitmq-data` holds the broker.
  - There is one network, `backend`. Only 8080 (`UI_PORT`) and 15672 (`RABBITMQ_MANAGEMENT_PORT`) are
    published.
  - `.env.example` holds placeholders only.
- **E5 `scripts/smoke.sh`** (bash 3.2+, curl, no jq). It uses `scripts/demo-filing.json`, built from
  `samples/sample-10k-excerpt.txt`. It checks:
  - the UI root (`text/html`), `js/app.js` (JavaScript MIME type) and `styles.css`;
  - 404 for `/mock/`, `/mock/server.js`, `/test/`, `/package.json` and `/README.md`;
  - invalid and malformed submits return 400 problem+json;
  - an unknown filing or report id returns 404 problem+json;
  - the demo submit returns 202 with `Location`, and `X-Correlation-Id` comes back unchanged;
  - status COMPLETED and a report with findings, both within 10 s (C2). Status and report are polled
    together, and each timing is printed;
  - the list contains the filing;
  - a filing of exactly 2,097,152 bytes returns 202, and one of 2,097,153 bytes returns 400 problem+json
    (not 413).

  Every failed check is printed and the script exits 1. When the status is stuck, it also checks
  whether the filing's events are in `ingestion.analysis-events.dlq`.
- **E6 `scripts/chaos.sh [a..f]`** (docker compose + curl). Each scenario prints PASS or FAIL with its
  duration and brings the stack back up afterwards. The script exits 1 if any scenario fails.
  - (a) Stop Analysis, then submit. The filing stays SUBMITTED for 5 s and the report is 404. Start
    Analysis; the filing reaches COMPLETED and the report has findings.
  - (b) Stop Reporting, then submit. The filing reaches COMPLETED, and the report is not available
    (nginx 503 problem+json, see the limitations). Start Reporting; the report reaches 200.
  - (c) Stop Analysis, then submit. Publish the same `analysis.completed` (same `eventId`, one
    `CHAOS-001` finding) twice through the management API. Reporting logs the second copy as a
    duplicate, and exactly one report with exactly one finding exists. The filing is COMPLETED. Start
    Analysis. The real result is logged as a late event, the report body stays byte-identical, the
    status stays COMPLETED, and nothing is dead-lettered.
  - (d) Publish an invalid body to `filing.submitted` through the management API. It appears in
    `analysis.filing-submitted.dlq`, which is peeked with requeue.
  - (e) Submit, then `docker compose restart ingestion-service` at once. The filing still reaches
    COMPLETED and gets its report.
  - (f) After COMPLETED, publish a late `analysis.started`. Ingestion logs it as a late event, the
    status stays COMPLETED, and the event is not in `ingestion.analysis-events.dlq`.
- **E7 `.github/workflows/ci.yml`** has two jobs.
  - `build`: `./mvnw -B verify` (Java 21) and `node --test frontend/test/` (Node 22).
  - `e2e`: needs `build`. It runs `docker compose up --build -d --wait`, `smoke.sh` and `chaos.sh`,
    prints `ps` and the logs on failure, and always runs `docker compose down -v`.

## Results on this machine (colima, arm64)
- `docker compose up --build -d --wait`: green in 16-32 s with a warm Maven cache.
- `scripts/smoke.sh`: every check passes except one. The report arrives about 0.4-1 s after the submit. The
  failing check is the filing status, which stays SUBMITTED (Ingestion bug below).
- `scripts/chaos.sh`: (c) passes in about 3.7 s and (d) in about 3.1 s. (a), (b), (e) and (f) fail
  because of the same Ingestion bug.
- `docker compose down -v`: green.
- I checked that the infrastructure and the scripts are correct with a throwaway, uncommitted copy of
  Ingestion in which the converter trusted the contract package. With it, smoke passed (status
  COMPLETED in 360 ms, report in 409 ms) and all six chaos scenarios passed:
  a 9.1 s, b 6.0 s, c 3.7 s, d 3.2 s, e 5.6 s, f 0.6 s. That copy was deleted; no service code was changed.

## Known issues and limitations
- **Service bug (Ingestion, blocking C2 and C3): Ingestion dead-letters every event that Analysis
  publishes, so the filing status never leaves SUBMITTED.**
  - Log: `IllegalArgumentException: The class 'com.veritrade.contracts.event.EventEnvelope' is not in
    the trusted packages: [java.util, java.lang]`, then `RejectAndDontRequeueRecoverer: Retries
    exhausted`. Every `analysis.started` and `analysis.completed` lands in
    `ingestion.analysis-events.dlq`.
  - Cause: Analysis publishes with `JacksonJsonMessageConverter`, which adds the `__TypeId__` header.
    Ingestion's `RabbitConfig.jsonMessageConverter()` bean (`ingestion-service/.../config/RabbitConfig.java`)
    is applied by Spring Boot to the listener container factory. Even though
    `AnalysisEventListener.onMessage(Message)` takes the raw message, `MessagingMessageListenerAdapter`
    converts the body first, and `DefaultJacksonJavaTypeMapper` refuses the class named in `__TypeId__`.
  - This breaks the contract rule "never dispatch on `__TypeId__`". Ingestion's ITs publish without
    that header, so they do not catch it. Reporting is not affected because it has no converter bean.
  - Suggested fix (Agent A): do not apply the JSON converter to the listener container (for example a
    listener factory with `SimpleMessageConverter`, or a type mapper that ignores the header), and add
    an IT that publishes with a `__TypeId__` header. Optionally, Analysis (Agent B) could stop sending
    the header.
- (b) cannot observe a 404 for the report while Reporting is stopped: a stopped upstream cannot answer.
  nginx answers `503 application/problem+json` instead, and the scenario asserts that.
- **Request to Agent D:** the UI's report polling treats only 404 as "not ready". A 503 while Reporting
  restarts ends the flow with an error. Consider retrying 502/503/504 like network errors.
- (e) uses a graceful restart (SIGTERM). The outbox publishes every 500 ms, so the event may already
  be sent before the restart. The scenario proves that nothing is lost across a restart; it does not
  force the row to be unpublished at kill time.
- `RABBITMQ_PASSWORD` takes effect only when the `rabbitmq-data` volume is created. After changing it,
  run `docker compose down -v`.
- On macOS (bash 3.2, BSD `date`) the scripts use `perl` for millisecond timings when it is available.
  Otherwise they fall back to whole seconds. Linux uses `date +%s%3N`.
- The scripts need ports 8080 and 15672 free on the host. Override them with `UI_PORT` and
  `RABBITMQ_MANAGEMENT_PORT` in `.env`; the scripts read `.env`.
- No Content-Security-Policy header is set yet.

## How to verify
- `docker compose up --build -d --wait`: all five containers report `healthy` (`docker compose ps`).
- `scripts/smoke.sh`: prints `Smoke test passed` (exit 0) once the Ingestion bug is fixed. Today the
  status check fails and the script names the dead-letter queue.
- `scripts/chaos.sh` (or `scripts/chaos.sh d f` for a subset): prints a PASS/FAIL summary with timings.
- `docker compose down -v`: removes the containers, the network and the volumes.
- RabbitMQ UI: http://localhost:15672 (user and password from `.env`, default `veritrade`/`veritrade`).

## AI record
- Raw record: [`03-orchestrator-waves-1-3.md`, subagent transcript `agent-ad6189fc38afe8617`](../ai-conversations/03-orchestrator-waves-1-3.md#subagent-transcript-agent-ad6189fc38afe8617)
- Asked for: Wave 1 infrastructure tasks E1-E7:
  - service and nginx images;
  - RabbitMQ with management UI;
  - Compose with health checks, volumes and only 8080/15672 published;
  - `smoke.sh` (C2 within 10 s, error codes, the 2 MB limit through nginx, MIME types, hidden files);
  - `chaos.sh` with six resilience scenarios through docker compose and the management API;
  - a two-job CI workflow;
  - real runs of all four commands, with service bugs reported and not fixed.
- Received:
  - the updated Java Dockerfile, the nginx Dockerfile, its ignore file and `default.conf`;
  - Compose and `.env.example`;
  - `scripts/lib/common.sh`, `smoke.sh`, `chaos.sh` and `demo-filing.json`;
  - `.github/workflows/ci.yml`;
  - real runs on colima that found the Ingestion `__TypeId__` dead-letter bug, plus a throwaway
    patched run showing that everything passes once that bug is fixed.
- Fixed by hand: nothing; the Ingestion bug the scripts found was fixed by Agent A, after which smoke and all six chaos scenarios passed

---

# Wave 3 - Agent E (Infrastructure, review fixes)

## Done
- **W3-04: published ports and broker credentials.**
  - Both published ports (UI 8080, management UI 15672) are bound to `BIND_ADDRESS`, default
    `127.0.0.1` (`docker-compose.yml`, `.env.example`).
  - Credential decision: the default `veritrade`/`veritrade` stays, so `docker compose up --build`
    still needs no manual step. It is no longer silent:
    - RabbitMQ now builds from `infra/docker/rabbitmq.Dockerfile`, which puts
      `infra/rabbitmq/credentials-guard.sh` in front of the official entrypoint.
    - A known weak password (`veritrade`, the `.env.example` placeholder `change-me`, `guest`, empty) on a
      loopback `BIND_ADDRESS` starts the broker with a `WARNING` line in its log.
    - The same weak password with any other `BIND_ADDRESS` (for example `0.0.0.0`) stops the container
      with an `ERROR` line, so `up --wait` fails. Publishing beyond loopback requires a real
      `RABBITMQ_PASSWORD`.
    - `smoke.sh` and `chaos.sh` print a warning when they run with a weak password.
  - The scripts reach the stack on `BIND_ADDRESS` (`localhost` for a wildcard address). The e2e harness
    removes `BIND_ADDRESS` from its environment, so it tests the compose default. It now reads the host
    from `docker compose port` instead of assuming `localhost`.
- **nginx timeouts.** `proxy_connect_timeout 2s`, `proxy_send_timeout 30s`, `proxy_read_timeout 30s`
  (named and commented), `resolver ... valid=5s` (was 10 s) and `resolver_timeout 2s`.
  - Cause of the old hang: for up to `valid` seconds after a stop, nginx still connects to the old
    container address. That address does not answer (ARP fails, `113: Host is unreachable`, about
    14-15 s), and the 60 s default connect timeout did not cut it short.
  - 503 timing for a request that hits the stale address:
    - before: 14.3 s (manual), 15 s curl timeout in chaos (b) against the old config;
    - after: 2.0 s (manual, 8 requests), slowest 2006-2011 ms in chaos (b).
    A stopped service whose name no longer resolves answers in about 30 ms.
  - The retry workarounds are gone: chaos (b) no longer uses `wait_for_report ... 503`, and
    `ServiceOutageE2E` no longer uses `awaitServiceUnavailable` or `Timeouts.UPSTREAM_GONE` (90 s).
- **Content-Security-Policy.** `infra/nginx/security-headers.conf` is included at server level and in
  `location /`, so every response carries it, including nginx's own problem responses:
  `default-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`.
  There is no `'unsafe-inline'`. `frontend/index.html` has no inline script, style or event handler,
  and `frontend/js` sets no inline styles and uses no `eval`, so nothing was requested from Agent D.

## Tests and checks
- **Bind address.**
  - `smoke.sh` checks that `docker compose port` shows `$BIND_ADDRESS` for `frontend 8080` and `rabbitmq 15672`.
  - `PublishedPortsE2E` checks that both bindings start with `127.0.0.1:`.
- **Credentials guard.**
  - `smoke.sh` runs only the check, `docker compose run --rm --no-deps rabbitmq check`, three times:
    a weak password on `0.0.0.0` is refused, a weak password on loopback warns, and a strong password
    on `0.0.0.0` passes silently.
  - `smoke.sh` and `PublishedPortsE2E` also check that the running broker logged the warning. Both skip
    this check when they run with a strong password.
- **CSP.**
  - `smoke.sh` checks the header on `/`, `js/app.js`, `styles.css`, an API response and nginx's 404
    problem, and checks that `index.html` has no inline code.
  - `StaticUiE2E` asserts the exact header on static files, API responses and nginx problem responses,
    and that `index.html` has no inline script, style, `on*=` handler or `javascript:` URL.
- **Fast 503.** The check probes the URL every 200 ms in the background while
  `docker compose stop` runs, and keeps probing for 7 s after it (longer than nginx's 5 s cache). This
  forces requests onto the stale address. Every answer must arrive in under 5 s, and every request sent
  after the stop must be a 503 problem+json, with no retry.
  - Implemented in chaos (b) (`stop_with_fast_unavailable`) and in `ServiceOutageE2E` (support class
    `UpstreamProbe`, `Timeouts.UPSTREAM_UNAVAILABLE` = 5 s, `UPSTREAM_ADDRESS_CACHE` = 7 s), for
    Reporting and Ingestion.
  - Chaos (b) against the old nginx config (copied into the running container) fails: 15 s, no answer.

## Results on this machine (colima, arm64)
- `docker compose up --build -d --wait`: green in about 15 s. Both ports are on `127.0.0.1`; RabbitMQ
  logs the warning.
- `scripts/smoke.sh`: passed (report after 104-676 ms).
- `scripts/chaos.sh`: all six passed (a 8.9 s, b 13.0 s, c 3.5 s, d 0.1 s, e 5.2 s, f 0.8 s).
- `docker compose down -v`: green.
- `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./mvnw -B -Pe2e verify -pl e2e-tests -am`:
  137 tests, 0 failures, in 4:00 (ServiceOutageE2E 104 s).

## Known issues and limitations
- The warning is in the rabbitmq container log (`docker compose logs rabbitmq`) and in the script
  output. `docker compose up -d` itself does not print container logs.
- The weak-password list is a fixed list. A different weak password counts as strong.
- `BIND_ADDRESS` also controls the UI port. A non-loopback UI has no authentication. That is the
  operator's explicit choice.
- `RABBITMQ_PASSWORD` is still stored in the `rabbitmq-data` volume on first start (`down -v` after
  changing it).
- The e2e suite reads `.env` through docker compose like before. A `.env` with a non-loopback
  `BIND_ADDRESS` makes `PublishedPortsE2E` fail by design.

## Requests to other owners
- **Agent G (docs):** in the README, say that the UI and the management UI listen on `127.0.0.1`
  only, list `BIND_ADDRESS`, and explain the default-password rule (warning on loopback, refused
  elsewhere). Mention the CSP if the README lists security headers.
- **Agent D (frontend):** no request. Keep the UI free of inline scripts, styles and `style=`/`on*=`
  attributes. `StaticUiE2E` and `smoke.sh` fail otherwise.

## How to verify
- `docker compose up --build -d --wait && docker compose ps`: the published ports show `127.0.0.1:`.
- `docker compose logs rabbitmq | head -1`: the default-password warning.
- `BIND_ADDRESS=0.0.0.0 docker compose up -d --wait`: fails, and `docker compose logs rabbitmq` shows
  the ERROR line.
- `scripts/smoke.sh`, `scripts/chaos.sh b` (prints the slowest answer across the stop), then
  `docker compose down -v`.

## AI record
- Raw record: [`03-orchestrator-waves-1-3.md`, subagent transcript `agent-a931918950f393e97`](../ai-conversations/03-orchestrator-waves-1-3.md#subagent-transcript-agent-a931918950f393e97)
- Fixed by hand: nothing; the branch merged without conflicts, and the requests above were done by Agent G.
