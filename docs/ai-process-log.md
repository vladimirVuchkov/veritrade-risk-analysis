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

#### [5] 2026-10-08 - Wave 3 (3.3-3.5) - Final review: orchestrator with agents B and F
- **Goal:** check the whole task before publishing: are all scenarios tested, are all sessions recorded, is everything in the plan marked, and is there a guide for manual testing; then close the gaps.
- **Tool and model:** Claude Code (Claude Opus 5.5); orchestrator session with a read-only coverage audit subagent, then two subagents in their own worktrees.
- **Given to the AI:** the plan (sections 1, 2, 8, 11, 15), the handoff notes and the test folders for the audit; for the agents, the gaps the audit found. Raw record: [`ai-conversations/04-orchestrator-final-review.md`](ai-conversations/04-orchestrator-final-review.md) (subagent transcripts included).
- **Received:**
  - Coverage audit: every scenario of the plan has a named test, mostly at unit, integration and end-to-end level; gaps: no 2 MB filing through the whole system, the Analysis "third outcome" (`analysis.failed` cannot be published) only as a unit test, no test of the retry backoff, stale test names in a handoff note.
  - Agent F: `LargeFilingE2E` (2 tests: exactly 2,097,152 bytes, ASCII and multibyte, phrases at the start, the middle and the last bytes all found); handoff note brought up to date.
  - Agent B: `AnalysisFailedUnpublishableIT` (a real negative confirm for `analysis.failed` sends the filing to the `.dlq` after 3 attempts, the next filing still flows; the backoff between attempts is at least the configured one).
  - Orchestrator: [`MANUAL-TESTING.md`](MANUAL-TESTING.md), every API step run against a fresh stack, including a `FAILED` filing on the real stack; links from every handoff note to its exact raw record; the missing record line of the Wave 2 documentation session; sections 3-5 of this log; plan sections 8, 11 and 15.
  - Records: the session of Waves 1-3 had continued after its export (the final commits), so `03` was exported again from the same transcript; this session is `04`.
- **My intervention:** _to be completed_
- **Decision:** _to be completed_
- **Verification:** a fresh clone of `main`: `docker compose up --build` (also with `--no-cache`), `scripts/smoke.sh` passed, `scripts/chaos.sh` six of six; on the merged branch `./mvnw -B verify` green (842 tests: contracts 29, Ingestion 327 + 26 IT, Analysis 286 + 20 IT, Reporting 137 + 17 IT), `node --test frontend/test/` 218/218, export tool 7/7, `./mvnw -B -Pe2e verify -pl e2e-tests -am` 146/146, and 147/147 after the CI fix below; each new test was shown to fail on broken behaviour. After the push (with the author's approval) the repository is public and clones without credentials. CI after the fix: all three jobs green (build and tests, Compose smoke and chaos, end-to-end 147/147).
- **Problems/lessons:**
  - The agents' worktrees started from `origin/main` (the Wave 0 skeleton), not from local `main`; both agents noticed and moved to local `main` before working. Unpushed work makes this easy to miss.
  - A record exported before the end of its session misses the last steps; the final export of a session is made after its last commit.
  - The first CI run of the end-to-end suite failed although it passed locally every time: on the GitHub runner Compose restarted nginx while bringing a dependency back, nginx got a new random host port, and the test client kept the old one, so every later request was refused. The client now looks the port up again after a refused connection, and `FrontendRestartE2E` reproduces the case (it fails with the same error without the fix). Everything had been local until this review, so CI had never run the suite before.
  - The next CI run (a docs-only commit) failed once in the frontend tests: on the slow runner the first status poll against the mock server came more than 60 ms after the submit, so the test never saw `SUBMITTED`. It did not fail locally even under load. The mock phases in the test are now 5 times longer (300/400/600 ms), well inside the polling limits.

## 3. Part of the system -> AI contribution -> my contribution

| Part | AI contribution | My contribution |
|---|---|---|
| Contract (`common-contracts`, `docs/contracts`) | Orchestrator (Wave 0): records, enums, topology constants, deterministic event ids, JSON Schemas, OpenAPI, examples, contract test; later decisions on event versioning and text limits | _to be completed_ |
| Ingestion | Agent A (Waves 1 and 3): REST API, outbox with publisher confirms, status state machine, correlation id filter, parking of poison outbox rows | _to be completed_ |
| Analysis | Agent B (Waves 1 and 3): YAML rules and loader, rule engine, the two failure paths, whitespace-tolerant matching | _to be completed_ |
| Reporting | Agent C (Wave 1): idempotent report store, first-terminal-event-wins rule, report API | _to be completed_ |
| Frontend | Agent D (Waves 1 and 3): UI, polling flow with cancellation, safe highlighting, mock server | _to be completed_ |
| Infrastructure | Agent E (Waves 1 and 3): Dockerfiles, Compose, nginx, credentials guard, `smoke.sh`, `chaos.sh`, CI | _to be completed_ |
| Tests | Every agent wrote the tests for its own folder; Agent F (Wave 2) wrote the end-to-end suite; every Wave 3 fix came with a regression test shown to fail on the old code | _to be completed_ |
| Documentation | Agent G (Waves 1-3): README, `architecture.md`, ADRs; the orchestrator: plan, agent rules, this log, `MANUAL-TESTING.md` | _to be completed_ |

## 4. Organisation of the parallel work

- **One orchestrator, many agents.** One Claude Code session acted as the orchestrator. It froze the
  contract in Wave 0, wrote each agent's prompt, merged the branches and ran the full checks. The
  agents were subagents of that session (14 in Waves 1-3, plus a Docker setup subagent in Wave 0 and
  the final review agents), each started in its own git worktree and branch.
- **Two agents at a time.** Wave 1 ran Ingestion, Analysis, Reporting, Frontend, Infrastructure and
  Documentation in pairs; Wave 2 ran Integration (F) next to Documentation (G); Wave 3 ran the review
  fixes in pairs.
- **One folder, one owner** ([`AGENT-RULES.md`](AGENT-RULES.md)). An agent wrote only in its folder
  and its handoff note. A change elsewhere was reported to the orchestrator, not made.
- **Conflicts.** Because folders did not overlap, the merges had no textual conflicts. The conflicts
  were in meaning, and the orchestrator decided them: the `rulesVersion` bump that touched the frozen
  contract example, the open question of a higher `eventVersion` (each consumer behaved differently),
  who cuts an over-long failure reason, and code points versus UTF-16 units in the length limits.
- **Merging.** Each wave was merged into an `integration/waveN` branch, verified there (Maven,
  frontend, end-to-end, Compose, smoke, chaos), then squashed into one commit on `main`.
- **Records.** Agents did not edit this log; each wrote an "AI record" at the end of its handoff note,
  and the orchestrator compiled them here.

## 5. What worked well with AI, and what did not

**Worked well**
- Freezing the contract before the parallel work: five agents built against it without waiting for
  each other, and the contract test caught drift at build time.
- Requiring every fix to come with a test that fails on the old code. Each agent reverted its change
  temporarily to prove it.
- Testing the real stack. The Compose runs and the end-to-end suite found bugs that every unit and
  integration test missed: the `__TypeId__` header that left filings `SUBMITTED`, the converter on the
  Analysis listener, code points versus UTF-16 units, and H2 losing commits on a killed container.
- An independent read-only review agent (Wave 3.1) that only listed findings; the fixes went to the
  owners of the code.
- Agents that reported a problem outside their folder instead of working around it (the Spring Boot
  4.1 retry property, the H2 2.4.240 CHECK-constraint bug).

**Did not work, or needed correction**
- Version drift: Spring Boot 4.1 dropped `max-attempts` silently, and the managed H2 version had a
  bug; both needed a human-style check with a minimal reproduction.
- An agent killed test processes with `pkill`, which could hit other agents on the same machine; the
  rules now forbid it.
- The first versions of the denylist check matched substrings and gave false positives (also inside
  Cyrillic words); it now matches whole words with Unicode word boundaries.
- The contract left some behaviour open (higher `eventVersion`, text limit units), and the agents
  made different choices until the orchestrator decided.
- Some handoff notes went stale after later waves (test names, counts); the final review fixed them.

## 6. Conclusion

_to be completed_
