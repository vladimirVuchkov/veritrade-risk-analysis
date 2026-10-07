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
- **Given to the AI:** the approved plan and the instruction to start Wave 0. Raw record: `ai-conversations/01-orchestrator-wave-0.md` (exported at the end of the session).
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
- **Verification:** `./mvnw -B verify` green (29 contract tests, 3 context tests); Flyway applies V1 in both services; the layered jar of a service starts with the Spring Boot launcher and serves `/actuator/health`; the export tool tested on a synthetic transcript (filtering, masking, denylist stop, turn removal).
- **Problems/lessons:**
  - Spring Boot 4 split the test starters per technology (`spring-boot-starter-webmvc-test`, `-data-jpa-test`, `-amqp-test`), and Testcontainers 2 renamed its modules (`testcontainers-rabbitmq`); names were checked against Maven Central instead of assumed.
  - json-schema-validator 3.x has a new API (`SchemaRegistry`); a negative test proves that the `$ref` between schemas is resolved and that invalid events are rejected.
  - The first version of the export tool printed the matched denylist terms in its own report, which would have leaked them into the next exported record. The report now masks the match and names only the denylist line.
  - The first version of the export tool matched denylist terms as substrings, which gave false positives inside longer words; it now matches whole words.

### W1 - Parallel implementation

_Entries are collected from the handoff notes in `handoff/`._

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
