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
