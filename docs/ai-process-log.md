# AI process log

This log is the readable summary of how AI assistants were used to build this project. The raw,
unedited conversation records are in [`ai-conversations/`](ai-conversations/); each entry below
links to the record it summarises.

## 1. Introduction

| Tool | Model | Used for |
|---|---|---|
| Claude Code (CLI) | Claude Opus 5.5 | Planning, orchestration, the shared contract, project skeleton, review |

The work is split into waves (see [`PLAN.md`](PLAN.md), section 8). In Wave 1 several agents work in
parallel, each in its own folder and git worktree, against a contract frozen in Wave 0.

## 2. Chronology

### W0 - Foundations

#### [1] 2026-10-07 - Wave 0 - Orchestrator
- **Goal:** create the repository skeleton and freeze the inter-service contract before the parallel work starts (plan tasks 0.1 to 0.7).
- **Tool and model:** Claude Code (Claude Opus 5.5)
- **Given to the AI:** the approved plan and the instruction to start Wave 0. Raw record: [`ai-conversations/01-orchestrator-wave-0.md`](ai-conversations/01-orchestrator-wave-0.md) (Docker setup subagent included).
- **Received:**
  - Parent `pom.xml` on Spring Boot 4.1.1 with every version pinned; Maven wrapper 3.9.16.
  - `common-contracts`: event envelope and payload records, enums, topology constants, deterministic event ids, correlation id conventions.
  - `docs/contracts/`: JSON Schemas (draft 2020-12), one example per event, OpenAPI 3.1, `messaging-topology.md`.
  - A contract test that validates every example against its schema, round-trips it through the records without loss, checks tolerant reading and checks enum parity between Java and the schemas.
  - Flyway `V1__init.sql` for Ingestion and Reporting; three service skeletons that start and expose health.
  - `docker-compose.yml` (RabbitMQ + three services) and one parameterised service Dockerfile.
  - `docs/AGENT-RULES.md`, this log, and `scripts/export-ai-conversation.py`.
- **My intervention:** _to be completed_
- **Decision:** _to be completed_
- **Verification:** `./mvnw -B verify` green (29 contract tests, 3 context tests); Flyway applies V1 in both services; the layered jar of a service starts with the Spring Boot launcher and serves `/actuator/health`; the export tool tested on a synthetic transcript (filtering, masking, denylist stop, turn removal); `docker compose up --build` brings up RabbitMQ and the three services, all healthy, running as a non-root user, with the H2 files on named volumes and only port 15672 published (the UI port is added with the frontend in Wave 1).
- **Problems/lessons:**
  - Docker was not installed on the development machine; a separate agent set up colima with the Docker CLI, Compose and Buildx and verified Testcontainers with a RabbitMQ container.
  - Spring Boot 4 split the test starters per technology (`spring-boot-starter-webmvc-test`, `-data-jpa-test`, `-amqp-test`), and Testcontainers 2 renamed its modules (`testcontainers-rabbitmq`); names were checked against Maven Central instead of assumed.
  - json-schema-validator 3.x has a new API (`SchemaRegistry`); a negative test proves that the `$ref` between schemas is resolved and that invalid events are rejected.
  - The first version of the export tool printed the matched denylist terms in its own report, which would have leaked them into the next exported record. The report now masks the match and names only the denylist line.
  - The first version of the export tool matched denylist terms as substrings, which gave false positives inside longer words; it now matches whole words.
  - The session was closed before its record was exported; it was exported the next day from the saved transcript. The denylist stopped the export on local Claude Code paths and on a name echoed by an early test run of the tool; these were masked with `--redact` (the name through its exact echoed form, so no other words changed).

### W1 - Parallel implementation

_Entries are collected from the handoff notes in `handoff/`._

#### [2] 2026-10-08 - Wave 1 - Orchestrator with agents A, B, C, D, E and G
- **Goal:** implement the three services, the UI, the infrastructure and the documentation in parallel (plan tasks A1-A6, B1-B5, C1-C5, D1-D5, E1-E7, G), then merge and verify the whole system.
- **Tool and model:** Claude Code (Claude Opus 5.5); one orchestrator session with one subagent per agent, each in its own git worktree and branch, two agents running at a time.
- **Given to the AI:** per agent, the ownership rules, the frozen contract, the plan tasks and a hard requirement for edge-case unit tests and integration tests. Raw record: exported by the orchestrator from its session (subagent transcripts included). Agent details: [`handoff/agent-a-ingestion.md`](handoff/agent-a-ingestion.md), [`handoff/agent-b-analysis.md`](handoff/agent-b-analysis.md), [`handoff/agent-c-reporting.md`](handoff/agent-c-reporting.md), [`handoff/agent-d-frontend.md`](handoff/agent-d-frontend.md), [`handoff/agent-e-infra.md`](handoff/agent-e-infra.md), [`handoff/agent-g-docs.md`](handoff/agent-g-docs.md).
- **Received:**
  - Ingestion: REST API with ProblemDetail errors, transactional outbox with publisher confirms, status state machine with the late-event rule.
  - Analysis: 33 YAML rules validated at startup, pure rule engine, the two failure paths (DLQ at once / `analysis.failed` after 3 attempts).
  - Reporting: idempotent report store (`processed_events`), first terminal event wins, report API.
  - Frontend: plain ES modules, polling flow, safe highlighting (no `innerHTML`), dependency-free mock server.
  - Infrastructure: service and nginx images, Compose with health checks, `smoke.sh`, `chaos.sh` (six resilience scenarios), CI workflow.
  - Documentation: README, `architecture.md` with Mermaid diagrams, ADRs 0001-0010.
- **My intervention:** _to be completed_
- **Decision:** _to be completed_
- **Verification:** every agent branch re-verified independently by the orchestrator, then merged into `integration/wave1`; `./mvnw -B verify` green (654 tests: contracts 29, Ingestion 259 + 20 IT, Analysis 183 + 12 IT, Reporting 134 + 17 IT); `node --test frontend/test/` 188/188; `docker compose up --build -d --wait` all five containers healthy; `scripts/smoke.sh` passed (report 950 ms after submit, limit 10 s); `scripts/chaos.sh` six of six scenarios passed.
- **Problems/lessons:**
  - Spring Boot 4.1 removed `spring.rabbitmq.listener.simple.retry.max-attempts` without a warning; Agent B found it and every service now uses `max-retries: 2` (3 attempts), asserted by an integration test.
  - H2 2.4.240 fails every insert into a table with a CHECK constraint once the connection that created it is closed. Reproduced by the orchestrator with a minimal JDBC program; H2 is pinned to 2.3.232 in the parent pom.
  - The contract `maxLength` values were ambiguous (code points vs UTF-16 units of the columns); the OpenAPI now states UTF-16 code units.
  - The merged build exposed a race in an Ingestion outbox integration test (the rejecting test queue was removed before the recovery queue was bound); fixed in the test.
  - The Compose chaos run found a real bug that all unit and integration tests missed: Ingestion's JSON converter bean was applied to the listener container and rejected every analysis event by its `__TypeId__` header, so filings stayed `SUBMITTED`. Agent A fixed it and added integration tests that publish with the header.
  - The UI treated a 503 from nginx (Reporting restarting) as a final error; Agent D made 502/503/504 retryable within a limit.
  - Testcontainers on macOS with colima needs `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`; documented in the README.

### W2 - Integration

### W3 - Review and delivery

## 3. Part of the system -> AI contribution -> my contribution

| Part | AI contribution | My contribution |
|---|---|---|
| Contract (`common-contracts`, `docs/contracts`) | | |
| Ingestion | | |
| Analysis | | |
| Reporting | | |
| Frontend | | |
| Infrastructure | | |
| Tests | | |
| Documentation | | |

## 4. Organisation of the parallel work

## 5. What worked well with AI, and what did not

## 6. Conclusion
