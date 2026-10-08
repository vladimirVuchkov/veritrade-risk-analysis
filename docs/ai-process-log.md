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
  - The session was closed before its record was exported; it was exported the next day from the saved transcript, in a short session of its own: [`ai-conversations/02-orchestrator-wave-0-record.md`](ai-conversations/02-orchestrator-wave-0-record.md). The denylist stopped the export on local Claude Code paths and on a name echoed by an early test run of the tool; these were masked with `--redact` (the name through its exact echoed form, so no other words changed).

### W1 - Parallel implementation

_Entries are collected from the handoff notes in `handoff/`._

#### [2] 2026-10-08 - Wave 1 - Orchestrator with agents A, B, C, D, E and G
- **Goal:** implement the three services, the UI, the infrastructure and the documentation in parallel (plan tasks A1-A6, B1-B5, C1-C5, D1-D5, E1-E7, G), then merge and verify the whole system.
- **Tool and model:** Claude Code (Claude Opus 5.5); one orchestrator session with one subagent per agent, each in its own git worktree and branch, two agents running at a time.
- **Given to the AI:** per agent, the ownership rules, the frozen contract, the plan tasks and a hard requirement for edge-case unit tests and integration tests. Raw record: [`ai-conversations/03-orchestrator-waves-1-3.md`](ai-conversations/03-orchestrator-waves-1-3.md) (subagent transcripts included). Agent details: [`handoff/agent-a-ingestion.md`](handoff/agent-a-ingestion.md), [`handoff/agent-b-analysis.md`](handoff/agent-b-analysis.md), [`handoff/agent-c-reporting.md`](handoff/agent-c-reporting.md), [`handoff/agent-d-frontend.md`](handoff/agent-d-frontend.md), [`handoff/agent-e-infra.md`](handoff/agent-e-infra.md), [`handoff/agent-g-docs.md`](handoff/agent-g-docs.md).
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

#### [3] 2026-10-08 - Wave 2 - Orchestrator with agents F and G
- **Goal:** end-to-end tests for every scenario of the whole system against the real Compose stack, fixes for what they find, and documentation that matches the merged code.
- **Tool and model:** Claude Code (Claude Opus 5.5); orchestrator session with two parallel subagents in their own worktrees.
- **Given to the AI:** Agent F: the list of 18 scenarios (happy path, validation, query edge cases, correlation id, each service and the broker down, duplicates, late and out-of-order events, failure path, poison messages, type headers, versions, concurrency, static UI, topology) and permission to fix only bugs its tests prove. Agent G: verify every infra-dependent statement in the docs and document the E2E suite. Raw record: [`ai-conversations/03-orchestrator-waves-1-3.md`](ai-conversations/03-orchestrator-waves-1-3.md) (subagent transcripts included). Details: [`handoff/agent-f-e2e.md`](handoff/agent-f-e2e.md), [`handoff/agent-g-docs.md`](handoff/agent-g-docs.md).
- **Received:**
  - `e2e-tests` module (Maven profile `e2e`): 132 tests in 13 classes on a Compose project of its own with random ports, plus the frontend polling flow run with Node against the real stack; a CI job for it.
  - Service fixes proven by the suite: Analysis kept the JSON converter on its listener (a `__TypeId__` header failed valid filings, unreadable JSON was retried); Reporting counted text limits in code points; with the broker down a stop exceeded Docker's grace period, the container was killed and H2 lost recent commits (fixed with a 2 s broker connection timeout).
  - README and architecture checked against the infrastructure; E2E, export tool tests and lessons documented.
- **My intervention:** _to be completed_
- **Decision:** _to be completed_
- **Verification:** `./mvnw -B verify` green (663 tests); `./mvnw -B -Pe2e verify -pl e2e-tests -am` 132/132 (two consecutive runs by Agent F, one by the orchestrator); `node --test frontend/test/` 188/188; export tool 7/7; `scripts/smoke.sh` passed; `scripts/chaos.sh` six of six.
- **Problems/lessons:**
  - An independent read-only review (Wave 3.1) ran in parallel with Agent F and confirmed the Analysis converter and Reporting length bugs as well; they were handed to Agent F, the remaining findings to Wave 3.
  - The export tool showed denylisted terms in its own report when one line held two of them (each report line masked only the term that triggered it). The orchestrator fixed it and added 7 tests; two of them fail on the old version.
  - The contract left open what a consumer does with an unknown higher `eventVersion` (Ingestion and Analysis processed it, Reporting dead-lettered it) and who enforces the failure-reason limit. The orchestrator decided both in `messaging-topology.md`: dead-letter in every consumer, Analysis cuts the reason.
  - nginx's default 60 s connect timeout made the first request to a just-stopped service hang about 20 s; the scripts and tests retry, and the nginx setting is a Wave 3 item.

### W3 - Review and delivery

#### [4] 2026-10-08 - Wave 3 - Orchestrator with agents A, B, D, E and G
- **Goal:** fix the findings of the independent review (W3-01 to W3-09) and the contract questions left open in Wave 2, each with a regression test that fails on the old code, then bring the documentation in line with the merged code.
- **Tool and model:** Claude Code (Claude Opus 5.5); orchestrator session with one subagent per owner in its own git worktree, two agents running at a time.
- **Given to the AI:** per agent, its review findings, the two contract decisions recorded in `messaging-topology.md` ("Event versioning", "Text limits") and the rule that every fix comes with a test proven to fail without it. Raw record: [`ai-conversations/03-orchestrator-waves-1-3.md`](ai-conversations/03-orchestrator-waves-1-3.md) (subagent transcripts included). Details: the Wave 3 sections of the notes in [`handoff/`](handoff/).
- **Received:**
  - Ingestion: correlation id accepted only as a strict ASCII token, otherwise replaced; outbox rows that the AMQP client refuses are parked after a bounded number of attempts (V2 migration) while broker failures never count; exponential back-off of the outbox run; status changes without loading the filing content; unsupported event versions and over-long reasons dead-lettered.
  - Analysis: whitespace-tolerant rule matching (`rulesVersion` 1.1), unsupported event versions dead-lettered without retries, the failure reason cut to 1000 UTF-16 units, excerpt limits, 2 s broker connection timeout.
  - Frontend: a new submit cancels the polling of the previous one (flow tokens, `AbortController`); a test that the UI needs no inline script or style.
  - Infrastructure: published ports bound to `127.0.0.1` by default (`BIND_ADDRESS`), the broker refuses a weak password on any other address, nginx answers 503 within about 2 s when a service is down, a Content-Security-Policy on every UI response.
  - Documentation: README, architecture and ADRs 0011-0014 updated to the merged code, with real test counts.
- **My intervention:** _to be completed_
- **Decision:** _to be completed_
- **Verification:** every agent branch re-verified independently by the orchestrator before the merge into `integration/wave3`; on the merged branch `./mvnw -B verify` green (840 tests: contracts 29, Ingestion 327 + 26 IT, Analysis 286 + 18 IT, Reporting 137 + 17 IT); `node --test frontend/test/` 218/218; export tool 7/7; `./mvnw -B -Pe2e verify -pl e2e-tests -am` 144/144; `docker compose up --build -d --wait` all containers healthy; `scripts/smoke.sh` passed; `scripts/chaos.sh` six of six; no denylisted term in the repository.
- **Problems/lessons:**
  - Raising `rulesVersion` was not in any agent's ownership (it touches the frozen contract example); the orchestrator made the bump in one commit with the example and the two tests that pin it.
  - Agent A stopped a stuck test run with `pkill -f surefire`, which could also have killed another agent's test process; the affected build was run again. Agents are now told not to kill processes they did not start.
  - A substring check of the denylist with `git grep -w` gave false positives inside Cyrillic words, because it treats non-ASCII letters as word boundaries; the check now uses Unicode word boundaries, like the export tool.
  - A parked outbox row leaves its filing `SUBMITTED` and there is no replay tool yet; recorded as a known limitation.

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
