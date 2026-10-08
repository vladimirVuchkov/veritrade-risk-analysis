# Handoff - Agent G (Documentation)

## Done
- `README.md` per PLAN.md section 10:
  - what the system is, with a Mermaid architecture diagram;
  - quick start;
  - how to run the tests (`./mvnw -B verify`, the colima socket override, `node --test
    frontend/test/`, smoke, chaos, CI, and a placeholder for the Wave 2 end-to-end suite);
  - repository layout;
  - decisions and trade-offs, each linked to its ADR;
  - resilience: each service down, idempotency, late events, retries, the two Analysis failure
    paths, the DLQs;
  - the overall risk-level rule as `RiskScorer` implements it;
  - production improvements, the AI tools section, and the known limitations from every handoff note.
- `docs/architecture.md`, with Mermaid diagrams for:
  - the components;
  - the happy path, the processing failure path, the poison message path and duplicate delivery
    (sequence diagrams);
  - the filing status state machine;
  - the outbox with publisher confirms;
  - the Analysis engine.

  It also has the event table and the messaging topology table (queues, DLQs, bindings, arguments).
- `docs/decisions/0001` to `0010`: RabbitMQ, a database per service, the shared contracts module,
  the outbox with confirms, one queue per consuming service, rules in YAML, choreography, the first
  terminal event wins, deterministic event ids, and plain HTML/JS behind nginx.
- Everything was written from the code on `integration/wave1`: `application.yml`, `RabbitConfig`,
  the listeners, `OutboxPublisher`, `FilingStatus`, `ReportService`, `RiskScorer` and
  `risk-rules.yml`. Where the code differs from the plan, the docs follow the code.

## Known issues and limitations
- **To verify after the infra merge.** These parts are marked `<!-- verify after infra merge -->`.
  They describe Agent E's work from PLAN.md E1-E7, because that work was not merged yet:
  - README: prerequisites and `docker compose up --build`; the addresses table (UI on 8080,
    management on 15672, default `veritrade` credentials from `docker-compose.yml`); the curl example
    through nginx; `scripts/smoke.sh`, `scripts/chaos.sh` and `scripts/demo-filing.json`; the CI
    workflow.
  - `docs/architecture.md`: the ports and nginx routes after the components diagram.
  - The README links to `infra/` but not to `scripts/smoke.sh`, `scripts/chaos.sh` or
    `.github/workflows/ci.yml`, because those files do not exist yet. Add the links after the merge.
  - On `integration/wave1` there is one parameterised `infra/docker/java-service.Dockerfile` (from
    Wave 0), not one Dockerfile per service as in E1, and Compose has no frontend/nginx service yet.
- The Wave 2 end-to-end subsection in the README is a placeholder.
- The test counts in the README (650 Java tests, 170 frontend tests) are as of `integration/wave1`.
- The AI tools section describes Wave 3 as planned. Update it when Wave 3 is done.
- Differences between the plan and the code (the docs follow the code):
  1. Retries: `max-retries: 2`, not `max-attempts: 3` (removed in Spring Boot 4.1). The result is
     still 3 attempts.
  2. Ingestion has no `processed_events` table. It detects duplicates through the status state
     machine (same target status = duplicate). Only Reporting has `processed_events`.
  3. Ingestion dead-letters an event for an unknown filing without retries. The plan does not cover
     this case.
  4. Analysis has a third outcome: if `analysis.failed` cannot be published, the message goes to the
     DLQ.
  5. Overall risk level: `NONE` when there are no findings. The escalation threshold is 10 findings.
     The plan only says "highest severity, raised when there are many findings".
  6. Reporting dead-letters events with `eventVersion` above 1, and events with an unknown enum
     value.
  7. Reporting returns 404 for a malformed filing id (the OpenAPI has no 400 there). The path is
     `/api/reports/{filingId}`, not `/reports/{filingId}` as in PLAN 6.3.
  8. Length limits count UTF-16 code units (the contract was updated). Reporting still validates
     incoming events in code points and then shortens text to the column size.
  9. Schema additions not in PLAN section 7: `filings.version` (optimistic locking) and
     `outbox.correlation_id`. The report response also has `bySeverity`.
  10. H2 is pinned to 2.3.232 instead of the Boot-managed 2.4.240.
  11. `risk-rules.yml` has 33 rules (the plan asks for at least 20). The UI lists 10 recent filings
      and gives up after 30 status polls and 15 report polls.
  12. Wave 0 Docker setup: one parameterised Dockerfile instead of one per service (see above).

## How to verify
- Link check: a small script outside the repository checks every relative link and same-file anchor
  in `README.md`, `docs/architecture.md`, `docs/decisions/*.md` and this note. Result: no broken
  links.
- `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./mvnw -B -q verify > build.log 2>&1; tail -n 50 build.log`
  exits 0. The reports show 650 tests and no failures: contracts 29; Ingestion 259 + 16 IT;
  Analysis 183 + 12 IT; Reporting 134 + 17 IT.
- `node --test frontend/test/` prints `tests 170`, `pass 170`, `fail 0` (Node 22).
- The Mermaid diagrams render in the GitHub Markdown preview.

## Wave 2
Branch `agent/docs-w2`, from `main` at `aa5bd3c` (Wave 1 merged). The Wave 1 parts above are kept as
they were written; where they differ, this part is current.

### Done
- Checked every `<!-- verify after infra merge -->` part against `docker-compose.yml`,
  `.env.example`, `infra/docker/*.Dockerfile`, `infra/nginx/default.conf`, `scripts/smoke.sh`,
  `scripts/chaos.sh`, `scripts/lib/common.sh`, `scripts/demo-filing.json`, `.github/workflows/ci.yml`
  and `docs/handoff/agent-e-infra.md`, corrected it and removed the marker. None is left.
  1. README, prerequisites and start: now `docker compose up --build -d --wait`, with the health
     checks and the start order. Added `docker compose down -v`.
  2. README, addresses: the env variables `UI_PORT` and `RABBITMQ_MANAGEMENT_PORT`; the credentials
     are `veritrade`/`veritrade` only without `.env` (`.env.example` has `change-me`); the internal
     ports; the password-only-on-volume-creation caveat.
  3. README, curl example: matches `FilingController` (202, `Location`, body). Added that nginx
     passes `X-Correlation-Id` both ways.
  4. README, smoke and chaos: the Wave 1 text undersold both scripts (smoke "plus a 2 MB filing";
     chaos only "stop Analysis"). Now it lists every smoke check and has a table of the six chaos
     scenarios (a)-(f), plus the graceful-restart caveat of (e).
  5. README, CI: the Wave 1 text said one job runs `docker compose down` after smoke. The real
     workflow has two jobs; the second also runs `scripts/chaos.sh`, prints logs on failure and runs
     `docker compose down -v`. The first job also runs the frontend tests.
  6. `docs/architecture.md`, ports and nginx: the marker paragraph pointed to PLAN.md. Replaced with
     the volumes, the `.env` ports and a new section 12 on the single parameterised Java Dockerfile
     (and why) and the nginx behaviour.
- Added links to `scripts/smoke.sh`, `scripts/chaos.sh`, `scripts/demo-filing.json`,
  `scripts/lib/common.sh`, `.github/workflows/ci.yml`, both Dockerfiles, `default.conf`,
  `docker-compose.yml` and `.env.example`.
- Corrected facts that changed after Wave 1:
  - Reporting down: nginx answers 503 problem+json (the README said "404, or 502 from nginx"), and the
    UI retries 502/503/504 and network errors, giving up after more than 3 in a row.
  - The `__TypeId__` bug: a README subsection "Lesson: the `__TypeId__` header", a paragraph in the
    architecture events section, and a line in the AI tools section.
  - Test counts: `./mvnw verify` 654 (contracts 29; Ingestion 259 + 20 IT; Analysis 183 + 12 IT;
    Reporting 134 + 17 IT); frontend 188.
  - Known limitations: no Content-Security-Policy, the RabbitMQ password caveat, script host ports and
    timing on macOS.
- ADR 0010: one consequence on nginx failure handling.

### Known issues and limitations
- The README end-to-end subsection was a placeholder (`<!-- filled after Wave 2 E2E merge -->`) until
  Agent F's `e2e-tests` module was merged; it is filled now (see below).
- The Java test counts are taken from the task, not from my own run: I did not run `./mvnw verify` in
  Wave 2, and I did not run Compose, because Agent F was using Docker and the ports.
- The AI tools section still describes Wave 3 as planned.

### How to verify
- Link check (script in my scratchpad, not in the repository) over `README.md`,
  `docs/architecture.md`, `docs/decisions/*.md` and this note: 79 relative links in 13 files,
  0 broken (anchors included).
- `node --test frontend/test/` (Node 22.23.2): `tests 188`, `pass 188`, `fail 0`.
- `grep -rn "verify after infra merge" README.md docs/architecture.md docs/decisions/` finds nothing.

### After the E2E merge
Branch `agent/docs-w2b`, from `integration/wave2` (Agent F's suite and my Wave 2 docs merged).
- README "End-to-end test suite": the placeholder and its marker are replaced. It covers the command
  `./mvnw -B -Pe2e verify -pl e2e-tests -am`, its own Compose project on random ports, access only
  through nginx and the management API, `down -v` at the end, a table of the 18 scenarios mapped to
  their test classes, about 4 min for 132 tests, the frontend flow against the real stack, and the
  CI job `e2e-suite` (now three CI jobs).
- README "Export tool": `python3 -m unittest discover -s scripts/tests` (7 tests).
- README "Lessons from the real stack" (it replaces "Lesson: the `__TypeId__` header"): the
  converter is kept off the listener in Analysis and Ingestion, Reporting counts UTF-16 units, and the
  broker-outage shutdown that lost H2 commits, fixed with `spring.rabbitmq.connection-timeout: 2s` in
  Ingestion and Reporting. The architecture events paragraph is updated to match.
- Known limitations: H2 can lose commits on a hard kill; the schema `maxLength` counts code points;
  a higher `eventVersion` gives a `COMPLETED` filing without a report; nginx's first request to a
  stopped service can hang about 20 s.
- Test counts: `./mvnw verify` 663 (no per-module split given), E2E 132, frontend 188, export tool 7.
- Repository layout: `e2e-tests/` and `scripts/tests/`.
- Checks: the link check finds 86 relative links in 13 files, 0 broken.
  `python3 -m unittest discover -s scripts/tests` ran 7 tests, OK. I ran neither Maven nor Compose;
  the 663 and 132 counts come from Agent F's handoff and the orchestrator.
- No placeholders are left in the README.

## Wave 3
Branch `agent/docs-w3`, from `integration/wave3` (the Wave 3 fixes of Agents A, B, D and E merged).
Everything was checked against the merged code and configuration; where an agent's handoff note and
the code differ, the docs follow the code.

### Done
- README:
  - Quick start: addresses on `127.0.0.1`; a new "Configuration" subsection with `BIND_ADDRESS`,
    the ports and the credentials, and the rule of `credentials-guard.sh` (weak password: `WARNING` on
    loopback, `ERROR` and no start elsewhere); the curl examples use `127.0.0.1`; the correlation id
    rule (strict ASCII token `[A-Za-z0-9._:-]`, 1-128, otherwise a generated UUID, never 400).
  - Tests: real counts (see below) with a per-module table; new integration test topics (parking,
    outage counts nothing, event versions, reason limit); frontend flow session, overlapping flows and
    `csp.test.js`; the new smoke checks (security headers, published ports, credentials guard);
    chaos (b) fast-503 probe; the E2E harness and loopback default; scenario rows 5, 7, 8, 15 and 17
    updated, rows 19 (`PublishedPortsE2E`) and 20 (`HardWrappedFilingE2E`) added.
  - Decisions table: ADRs 0011-0014.
  - Resilience: the broker-down row (outbox back-off 1 s to 10 s, 2 s connection timeout in all three
    services); nginx fast 503 (`proxy_connect_timeout 2s`, `resolver valid=5s`); event versioning and
    text limits linked to `docs/contracts/messaging-topology.md`, not repeated; the Analysis failure
    paths and the DLQ table; a new subsection "Outbox rows that can never be published"; the
    `__TypeId__` lesson (Ingestion now has no converter at all); the H2 lesson (Analysis also has the
    2 s timeout).
  - Overall risk level: `rulesVersion` 1.1 and whitespace-tolerant matching (`[\h\v]+`, NBSP).
  - Production: a replay tool for parked outbox rows. AI tools: the Wave 3 row is no longer "planned".
  - Known limitations: removed "no CSP" and "first request hangs about 20 s"; replaced the
    `eventVersion` item; added the parked row (filing stays `SUBMITTED`, no replay tool), the confirm
    channel per refused message, the back-off delay, no UI authentication, the fixed weak-password
    list, the untested headless CSP check, the flow tokens, and the hyphen broken by a line break.
- `docs/architecture.md`: published ports on `BIND_ADDRESS`; event versioning and text limits (linked);
  the converter paragraph; the reason limit; the poison paths; the status change through
  `FilingStatus.transitionTo` and a versioned JPQL update without the content; the outbox diagram
  redrawn with back-off and parking; the Analysis engine (whitespace tolerance, excerpt and matched
  text limits); the correlation id rule; a new "RabbitMQ image" subsection; nginx timeouts, fast 503
  and the CSP.
- ADRs: new 0011 (park poison outbox rows), 0012 (replace an invalid correlation id), 0013 (loopback
  by default and the broker password guard), 0014 (dead-letter an unsupported event version or an
  over-long text). "Update (Wave 3)" sections in 0004, 0006 and 0010.

### Known issues and limitations
- `docs/handoff/agent-b-analysis.md` says `rulesVersion` stays `"1.0"`, but the merged code has
  `"1.1"` (commit "Bump rulesVersion to 1.1 for whitespace-tolerant matching", together with the
  contract example). The docs say 1.1. That note is not mine to change.
- The Compose stack, `scripts/smoke.sh` and `scripts/chaos.sh` were not run by me; their behaviour is
  documented from the scripts and Agent E's handoff (including the 2 s and about 15 s timings).
- The headless-browser check under the CSP was not run by anyone (Agent D's note).

### How to verify
- `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./mvnw -B verify` (with `DOCKER_HOST`
  pointing at the colima socket): BUILD SUCCESS, 840 tests: contracts 29; Ingestion 327 + 26 IT;
  Analysis 286 + 18 IT; Reporting 137 + 17 IT.
- `./mvnw -B -Pe2e verify -pl e2e-tests -am`: BUILD SUCCESS, 144 tests in 14 classes, 4:16 min; the
  stack was removed afterwards.
- `node --test frontend/test/` (Node 22.23.2): `tests 218`, `pass 218`, `fail 0`.
- `python3 -m unittest discover -s scripts/tests`: 7 tests, OK.
- Link check (script in my scratchpad, not in the repository) over `README.md`,
  `docs/architecture.md`, `docs/decisions/*.md` and this note: 0 broken links (anchors included).

### AI record
- Raw record: exported by the orchestrator from its session (subagent transcript)
- Asked for: make the README, the architecture document and the ADRs match the merged Wave 3 code,
  with every statement checked against the code, real test counts, ADRs only for real Wave 3
  decisions, and links to the contract rules instead of copies.
- Received: the changes above in three commits, plus this section.
- Fixed by hand: nothing; the orchestrator updated the `rulesVersion` note in the Analysis handoff that this agent reported as stale.

## AI record
- Raw record: exported by the orchestrator from its session (subagent transcript)
- Asked for: Wave 2 Agent G. That meant the README per PLAN.md section 10, `docs/architecture.md`
  with Mermaid diagrams (components, four sequence diagrams, the status state machine, the outbox,
  the topology table) and ten short ADRs, all written from the actual code. Also a link check, a run
  of the documented test commands, the infra parts marked for verification, a placeholder for the
  Wave 2 end-to-end suite, and a list of differences between the plan and the code.
- Received: the README, `docs/architecture.md`, `docs/decisions/0001`-`0010` and this note on
  `agent/docs`. All relative links resolve, `./mvnw -B -q verify` is green (650 tests), and
  `node --test frontend/test/` passes 170 tests. Twelve differences between the plan and the code
  are listed above.
- Fixed by hand: nothing yet; the parts marked `verify after infra merge` are checked in Wave 2
