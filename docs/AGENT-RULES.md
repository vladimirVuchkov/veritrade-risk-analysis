# Rules for agents

These rules apply to every agent that works on this repository in parallel (see `docs/PLAN.md`,
section 8). The orchestrator merges all work into `main`.

## Ownership

| Agent | Writes only in | Branch |
|---|---|---|
| A - Ingestion | `ingestion-service/` | `agent/ingestion` |
| B - Analysis | `analysis-service/` | `agent/analysis` |
| C - Reporting | `reporting-service/` | `agent/reporting` |
| D - Frontend | `frontend/` | `agent/frontend` |
| E - Infrastructure | `infra/`, `scripts/` (except `export-ai-conversation.py`), `docker-compose.yml`, `.env.example`, `.github/` | `agent/infra` |
| G - Documentation | `README.md`, `docs/` (except `docs/contracts/` and `docs/ai-process-log.md`) | `agent/docs` |
| Orchestrator | `pom.xml`, `common-contracts/`, `docs/contracts/`, `docs/ai-process-log.md`, `.gitignore`, `.dockerignore`, `.mvn/`, `mvnw*` | `main` |

1. **One folder, one owner.** Write only in your own folders. A change anywhere else is a request to
   the orchestrator, with the reason.
2. **Work in your own git worktree on your own branch.** Do not merge or rebase other agents' branches.
   The orchestrator merges.
3. Every agent also writes its own `docs/handoff/<agent>.md` (see below).

## The contract is frozen

- `common-contracts/` and `docs/contracts/` are read-only. They define the events, the REST API,
  the messaging topology and the shared enums.
- Never redefine an event record, an enum or a queue name inside a service. Import it from
  `common-contracts`.
- If the contract looks wrong or incomplete, stop and ask the orchestrator. Do not work around it.
  When the orchestrator changes the contract, every agent is told.
- The Flyway `V1__init.sql` migrations of Ingestion and Reporting are fixed. A schema change is a new
  migration (`V2__...sql`) in your own service, agreed with the orchestrator first.

## Build and dependencies

- Every version is pinned in the parent `pom.xml`. Touch only the `pom.xml` of your own service and
  never write a `<version>` there. A missing dependency is a request to the orchestrator.
- Java 21, Spring Boot 4.1, Jackson 3 (`tools.jackson.*`), Spring AMQP 4.1. The JSON message
  converter is `JacksonJsonMessageConverter`.
- Quiet build, then read only what you need:
  `./mvnw -B -q -pl <your-service> -am verify > build.log 2>&1; tail -n 50 build.log`
- Unit tests are `*Test` (surefire); integration tests with Testcontainers are `*IT` (failsafe).
  Testcontainers uses dynamic ports, so agents never collide.
- Leave `./mvnw -B verify` green on your branch.

## Code rules

- Package root `com.veritrade.<service>`. Layers `api` -> `service` -> `repository`, plus `messaging`,
  `domain`, `config`. Dependencies point downwards only; `domain` has no Spring imports.
  An ArchUnit test in every service enforces this.
- Constructor injection. Records for DTOs. No Lombok.
- No magic numbers: thresholds and limits go to `@ConfigurationProperties`.
- One responsibility per class; methods up to about 25 lines.
- No dead code, no commented-out code, no comments that repeat the code.
- REST errors are RFC 9457 `ProblemDetail`.
- Log with the `correlationId` in the logging context (`CorrelationIds.MDC_KEY`).
- No secrets, no personal paths, no machine names in the code, the configuration or the docs.

## Messaging rules (summary of `docs/contracts/messaging-topology.md`)

- Declare exactly the topology you use, with exactly the documented names and arguments.
- At-least-once delivery: deduplicate by `eventId`; event ids come from `EventIds.forFiling`.
- `COMPLETED` and `FAILED` are final; the first terminal event wins. A late or contradictory event
  is acknowledged and ignored with a `WARN` log line. It never throws.
- Retries use the Spring AMQP listener retry (configured in `application.yml`), then the dead-letter queue.
  An unreadable message goes straight to the dead-letter queue.
- Tolerant reader: ignore unknown fields; dispatch on `eventType` or the routing key, never on a
  Java type header.

## Commits

- Small commits with short imperative messages, for example `Add outbox publisher with publisher confirms`.
- Commit only to your own branch.

## Handoff note

When you finish, write `docs/handoff/<agent>.md`:

```
# Handoff - <agent>

## Done
- ...

## Known issues and limitations
- ...

## How to verify
- commands and what they show

## AI record
- Raw record: docs/ai-conversations/NN-<agent>-<topic>.md
- Asked for: ...
- Received: ...
- Fixed by hand: ...
```

Do not edit `docs/ai-process-log.md`; the orchestrator collects the handoff notes into it.

## Conversation records

- One session per task, started clean, with the repository (or your worktree) as the working directory.
  Nothing unrelated to this project is discussed in it.
- At the end, the session is exported with `scripts/export-ai-conversation.py` into
  `docs/ai-conversations/NN-<agent>-<topic>.md`. Wording is never edited. The only allowed changes
  are masking secrets or personal data with `[REDACTED]` and replacing a whole unrelated exchange
  with `[unrelated exchange removed]`.
