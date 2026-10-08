# Development Plan — VeriTrade Risk Analysis

> **Structured for execution by multiple agents.** The work is split into waves. Tasks inside a wave are independent and can start at the same time. Each agent owns specific folders, so agents do not interfere with each other. The shared source of truth between agents is the contract frozen in Wave 0 (section 4).

Source: the VeriTrade prescreen assignment (risk analysis service for financial filings).

---

## 1. Goal and success criteria

A prototype made of three Java microservices (Ingestion, Analysis, Reporting), a simple web UI, and complete documentation. The evaluation is about the design of the distributed system, not about the quality of the "AI" analysis.

| # | Criterion | How it is verified |
|---|---|---|
| C1 | `docker compose up --build` starts everything with one command | manual + `scripts/smoke.sh` |
| C2 | Submit a filing, see the report in the UI in under 10 seconds | smoke test |
| C3 | Resilience: a stopped service does not lose messages; a redelivered message creates no duplicates; a poison message ends up in a dead-letter queue | integration tests |
| C4 | Clean and tested code: unit tests in every service plus at least one integration test | `mvn verify` is green |
| C5 | README with decisions and trade-offs, "AI tools" section, conversation records committed | checklist review (section 11) |

---

## 2. Fixed decisions (so they are not reopened)

| Topic | Decision | Rationale / trade-off |
|---|---|---|
| Language and framework | Java 21 (LTS), latest GA Spring Boot 4.x, Maven with wrapper (`mvnw`); exact versions pinned in Wave 0 | current supported line; familiar ecosystem. Spring Boot 4 ships Jackson 3, so contract records carry no Jackson annotations |
| Communication | **Asynchronous through RabbitMQ.** REST only for external calls (UI to Ingestion, UI to Reporting) | analysis is slow; one service going down does not stop intake. Cost: eventual consistency, harder testing |
| Data | Each service has its own **file-mode H2** (Docker volume). No shared database | loose coupling, easy to run. Cost: no cross-service queries; production would use PostgreSQL |
| Contracts | **A shared `common-contracts` module** (plain Java: event envelope, event payload records, shared enums, messaging constants, deterministic event-id helper). JSON Schemas and examples in `docs/contracts/` are the language-neutral reference, and one test inside the module checks the records against them. Service-specific REST DTOs, entities and logic stay inside each service | one definition, compile-time safety, no copy-paste. Cost: services share a build (acceptable in a single repository with a single multi-module build); the module must stay small and free of business logic |
| Analysis rules | Rules in a YAML file (`risk-rules.yml`): category, keywords/regexes, severity | easy to read and extend |
| Frontend | Plain HTML + JS (Fetch) served by nginx; nginx is also the reverse proxy to both APIs | no CORS, no Node in the stack |
| Filing status | `SUBMITTED -> ANALYZING -> COMPLETED \| FAILED`, owned by Ingestion | single source of truth for status |
| Idempotency | `eventId` and `filingId` as keys; `processed_events` table; Reporting also upserts by `filingId`. Analysis derives `eventId` deterministically (name-based UUID of `filingId` + event type), so a re-analysis of a redelivered filing produces the same `eventId` | messages are delivered at least once |
| Late or conflicting events | Terminal states (`COMPLETED`, `FAILED`) are final; the first terminal event wins. A late or backward transition is acknowledged and ignored with a WARN log, never thrown | throwing would send a harmless message to the dead-letter queue |
| Retries | Spring AMQP built-in listener retry (`spring.rabbitmq.listener.simple.retry.*`: 3 attempts, exponential backoff), then a recoverer; not `@Retryable` | predictable failure behavior, configured in one place |
| Failure paths in Analysis | (a) a message that cannot be deserialized or validated goes straight to the dead-letter queue, no retries; (b) a processing failure after the last attempt publishes `analysis.failed`, then the message is acknowledged | a poison message never blocks the queue; a real failure is visible to the user. Cost: in case (a) the filing stays `SUBMITTED` (documented limitation) |
| Outbox pattern | **Ingestion only** (database write and event publication become atomic through an `outbox` table and a scheduled publisher). A row is marked published only after a positive **publisher confirm** (`publisher-confirm-type: correlated`, `mandatory` returns); single publisher instance, rows sent in `created_at` order | shows maturity; without confirms the outbox would guarantee nothing. If time is short, it is documented as a trade-off in the README |
| Event evolution | Envelope carries `eventVersion`; consumers ignore unknown fields (tolerant reader); AMQP `messageId` = `eventId`, `contentType` = `application/json`, `correlationId` set; persistent messages, durable queues | old and new service versions stay compatible in both directions |
| Payload size | The filing text (up to 2 MB) travels inside `filing.submitted` and the outbox row | simple for a prototype. Production: claim check (text in object storage, the event carries a reference) |
| Errors | RFC 9457 `ProblemDetail` (built into Spring) | standard, no custom error format |
| Observability | Spring Actuator `/actuator/health`, `correlationId` in all logs | |
| Tests | JUnit 5, Mockito, AssertJ, Testcontainers (RabbitMQ) for integration tests | |
| Coordination style | **Choreography** (services react to events), no central orchestrator service | fewer moving parts; the trade-off is explained in the README |

---

## 3. Architecture

### 3.1 Flow

```
 Browser
    |  (1) POST /api/filings            (5) GET /api/filings/{id}  (status)
    v                                   (6) GET /api/reports/{id}  (report)
 nginx (frontend) ---------------+-----------------------------+
                                 v                             v
                         +--------------+               +--------------+
                         |  INGESTION   |               |  REPORTING   |
                         |  :8081       |               |  :8083       |
                         |  db: filings, outbox         |  db: reports, findings,
                         +------+-------+               |      processed_events
                                | (2) filing.submitted  +------^-------+
                                v                              | (4) analysis.completed / failed
                         ================= RabbitMQ ============+========
                         exchange: veritrade.events (topic)
                                          v (3)
                                  +--------------+
                                  |  ANALYSIS    |  :8082 (health only)
                                  |  no database |  rules from risk-rules.yml
                                  +--------------+
   Ingestion also listens to analysis.* (one queue) and updates the status
```

### 3.1.1 Ports

| Component | Port | Published to the host |
|---|---|---|
| Frontend (nginx) | 8080 | yes |
| Ingestion | 8081 | no (Compose network only) |
| Analysis (health only) | 8082 | no |
| Reporting | 8083 | no |
| RabbitMQ AMQP / management | 5672 / 15672 | management only |

### 3.2 Events (exchange `veritrade.events`, type topic)

| Routing key | Published by | Content |
|---|---|---|
| `filing.submitted` | Ingestion | filingId, title, company, text |
| `analysis.started` | Analysis | filingId, startedAt |
| `analysis.completed` | Analysis | filingId, findings, summary |
| `analysis.failed` | Analysis | filingId, reason |

**One consumer queue per service** (a single queue keeps the order of events for a filing and keeps the topology small):

| Queue | Owner | Bindings |
|---|---|---|
| `analysis.filing-submitted` | Analysis | `filing.submitted` |
| `ingestion.analysis-events` | Ingestion | `analysis.*` |
| `reporting.analysis-results` | Reporting | `analysis.completed`, `analysis.failed` |

Every queue has a `<name>.dlq` dead-letter queue through the exchange `veritrade.dlx`.

Common envelope of every event: `eventId` (UUID), `eventType`, `eventVersion` (integer, starts at 1), `occurredAt` (ISO-8601 UTC), `correlationId`, `payload`.

### 3.3 REST contract (external)

| Service | Request | Response |
|---|---|---|
| Ingestion | `POST /api/filings` `{companyName, title, content}` | `202` `{filingId, status}` + `Location: /api/filings/{id}`; `400` on invalid input (empty text, over the 2 MB limit) |
| Ingestion | `GET /api/filings/{id}` | `200` `{filingId, companyName, title, status, submittedAt, failureReason?}`; `404` |
| Ingestion | `GET /api/filings?limit=` | list, newest first (for the UI table); default 20, max 100 |
| Reporting | `GET /api/reports/{filingId}` | `200` report; `404` while not ready (the UI keeps polling) |
| all | `GET /actuator/health` | `200` |

All error responses are RFC 9457 `ProblemDetail` (`application/problem+json`).

---

## 4. Inter-service contract (frozen by Wave 0)

### 4.1 Shared module `common-contracts`

A plain Java module (no Spring) that every service depends on:

```
common-contracts/
  event/      EventEnvelope<T>, EventType,
              FilingSubmittedPayload, AnalysisStartedPayload,
              AnalysisCompletedPayload (+ AnalysisSummary, FindingPayload), AnalysisFailedPayload
  model/      RiskCategory, Severity, RiskLevel
  messaging/  MessagingTopology (exchange, routing keys, queue names as constants),
              EventIds (deterministic name-based event ids)
  logging/    CorrelationIds (header name, log context key)
```

Rules: only contract types live here. No entities, repositories, controllers, service logic or Spring configuration. Records are immutable, plain (no Jackson annotations), and serialize with the default Jackson settings; consumers are configured to ignore unknown fields. A test in the module serializes and parses every file in `docs/contracts/examples/` and validates it against the JSON Schemas, so the contract is verified once for everyone.

### 4.2 Contract documents

Files in `docs/contracts/`:

- `event-envelope.schema.json`
- `filing-submitted.schema.json`
- `analysis-started.schema.json`
- `analysis-completed.schema.json`
- `analysis-failed.schema.json`
- `rest-api.openapi.yaml` (Ingestion + Reporting)
- `messaging-topology.md` — exact exchange, queue, binding, DLQ and argument definitions (TTL, dead-letter settings), AMQP message properties, and the rule for late or conflicting events (section 2). Every service declares the topology it uses in its own `RabbitConfig` with exactly these arguments, so declarations are idempotent and startup order does not matter
- `examples/` — one valid example per event (used by the module's contract test and by the services' tests)

Core of `analysis-completed.payload`:

```json
{
  "filingId": "uuid",
  "analyzedAt": "2026-10-07T12:00:00Z",
  "rulesVersion": "1.0",
  "summary": { "totalFindings": 7, "overallRiskLevel": "HIGH",
               "byCategory": { "CYBERSECURITY": 2, "LEGAL": 3 } },
  "findings": [
    { "category": "LEGAL", "severity": "HIGH", "ruleId": "LEGAL-001",
      "matchedText": "...pending litigation...", "excerpt": "...", "position": 1520 }
  ]
}
```

Categories: `FINANCIAL`, `LEGAL`, `OPERATIONAL`, `CYBERSECURITY`, `REGULATORY`, `MARKET`.
Severity: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`. Overall risk level: the highest severity found, raised one level when there are many findings (the exact rule lives in Analysis and is described in the README).

**Change rule:** once frozen, `common-contracts` and `docs/contracts/` are changed only by the orchestrator, and all agents are notified. An agent never changes them on its own.

---

## 5. Repository and structure

Name: `veritrade-risk-analysis`. Public on GitHub.

```
veritrade-risk-analysis/
├── README.md
├── docker-compose.yml
├── .env.example
├── .gitignore
├── .dockerignore
├── mvnw, mvnw.cmd, .mvn/           # Maven wrapper
├── pom.xml                         # parent (shared versions and modules only)
├── common-contracts/               # shared contract module (orchestrator)
├── .github/workflows/ci.yml
├── scripts/
│   ├── smoke.sh                    # end-to-end check after compose up
│   ├── chaos.sh                    # stop/start a service, verify recovery
│   ├── export-ai-conversation.py   # session export for docs/ai-conversations/
│   └── demo-filing.json
├── samples/
│   └── sample-10k-excerpt.txt
├── docs/
│   ├── PLAN.md                     # this plan
│   ├── architecture.md             # diagrams, event flows, rationale
│   ├── AGENT-RULES.md              # rules every agent follows
│   ├── contracts/                  # section 4
│   ├── decisions/                  # short ADR files (0001-rabbitmq.md ...)
│   ├── handoff/                    # one note per agent
│   ├── ai-process-log.md           # readable account of the AI-assisted process
│   └── ai-conversations/           # raw conversation exports (required)
├── ingestion-service/              # agent A
├── analysis-service/               # agent B
├── reporting-service/              # agent C
├── frontend/                       # agent D
└── infra/                          # agent E (Dockerfiles, nginx)
```

### 5.1 Repository creation steps (Wave 0, orchestrator)
1. `git init`, branch `main`; `.gitignore` with technical entries only (target/, .idea/, *.db, .env, build.log); `.dockerignore` (.git, target, docs, frontend for the Java images).
2. Parent `pom.xml` with the modules and **every** version the agents will need in `dependencyManagement` (Spring Boot BOM, Testcontainers BOM, Flyway, JSON Schema validator, ArchUnit), Java 21, test plugins; Maven wrapper. Agents change only their own service `pom.xml`.
3. Folders from the structure above, with a `.gitkeep` in each.
4. First commit: "Skeleton and contract".
5. Create the public GitHub repository and push `main`.
6. Working branches: `agent/ingestion`, `agent/analysis`, `agent/reporting`, `agent/frontend`, `agent/infra`; merged into `main` by the orchestrator.

---

## 6. Class layout (per service)

Package root: `com.veritrade.<service>`. Layers: `api` -> `service` -> `repository`, plus `messaging`, `domain`, `config`. Dependencies point only downward; `domain` knows nothing about Spring.

### 6.1 Ingestion (`com.veritrade.ingestion`)

```
api/
  FilingController            POST /filings, GET /filings, GET /filings/{id}
  dto/ SubmitFilingRequest, FilingResponse, FilingStatusResponse
  GlobalExceptionHandler      RFC 9457 ProblemDetail responses
domain/
  Filing                      (id, companyName, title, content, status, submittedAt, failureReason)
  FilingStatus                enum SUBMITTED, ANALYZING, COMPLETED, FAILED
  OutboxEvent                 (id, routingKey, payloadJson, createdAt, publishedAt)
service/
  FilingService               accepts, validates, stores Filing + OutboxEvent in one transaction
  FilingStatusService         applies transitions (rejects invalid ones, e.g. COMPLETED -> ANALYZING)
messaging/
  OutboxPublisher             @Scheduled; sends unpublished rows in order, sets publishedAt after a positive confirm
  AnalysisEventListener       one queue (analysis.*), dispatches by eventType -> FilingStatusService
repository/
  FilingRepository, OutboxRepository
config/
  RabbitConfig                exchange, queues, DLQ, JSON converter
```

```
SubmitFilingRequest -> FilingController -> FilingService -> FilingRepository
                                              \-> OutboxRepository <- OutboxPublisher -> RabbitMQ
RabbitMQ -> AnalysisEventListener -> FilingStatusService -> FilingRepository
```

### 6.2 Analysis (`com.veritrade.analysis`)

```
messaging/
  FilingSubmittedListener     receives the event; unreadable -> DLQ at once; processing failure after retries -> analysis.failed
  AnalysisEventPublisher      started / completed / failed
engine/
  RiskAnalyzer                orchestrates: text -> findings -> summary
  RuleLoader                  reads risk-rules.yml at startup, validates and precompiles the patterns
  RiskRule                    (id, category, severity, patterns[])
  RuleMatcher                 applies a pattern to the text -> Match (position, excerpt); capped per rule (configurable, default 50)
  ExcerptExtractor            surrounding context around a match (+-120 characters)
  RiskScorer                  overall risk level from the findings
domain/
  Finding, AnalysisResult   (event records and enums come from common-contracts)
config/
  RabbitConfig, RulesProperties
resources/
  risk-rules.yml              at least 20 rules in 6 categories
```

```
FilingSubmittedListener -> RiskAnalyzer -> RuleMatcher (over RiskRule from RuleLoader)
                                       |-> ExcerptExtractor
                                       \-> RiskScorer -> AnalysisResult -> AnalysisEventPublisher
```

The analyzer is **pure logic with no Spring and no broker**, so it is fast and easy to test.

### 6.3 Reporting (`com.veritrade.reporting`)

```
api/
  ReportController            GET /reports/{filingId}
  dto/ ReportResponse, FindingResponse, ReportSummaryResponse
domain/
  Report                      (filingId, status, generatedAt, overallRiskLevel, totalFindings, rulesVersion, failureReason)
  FindingEntity               (id, report, category, severity, ruleId, matchedText, excerpt, position)
  ProcessedEvent              (eventId, processedAt)  <- idempotency
service/
  ReportService               builds/stores the report, per-category summaries
  IdempotencyGuard            "have I seen this eventId?"
messaging/
  AnalysisResultListener      one queue (completed + failed), dispatches by eventType
repository/
  ReportRepository, ProcessedEventRepository
config/
  RabbitConfig
```

### 6.4 General code rules
- Constructor injection; `record` for DTOs; event types are never redefined inside a service (they come from `common-contracts`); no Lombok (less magic, easier to explain).
- No magic numbers: thresholds and limits live in `@ConfigurationProperties`.
- No dead code and no comments that repeat the code.
- One responsibility per class; methods up to ~25 lines.
- An ArchUnit test in every service enforces the layering (`api` -> `service` -> `repository`) and keeps `domain` free of Spring.

---

## 7. Data

| Service | Tables |
|---|---|
| Ingestion | `filings(id PK, company_name, title, content, status, submitted_at, updated_at, failure_reason)`, `outbox(id PK, routing_key, payload, created_at, published_at NULL)` |
| Analysis | none (in memory only); deterministic event ids make redelivery safe without a table |
| Reporting | `reports(filing_id PK, status, overall_risk_level, total_findings, rules_version, generated_at, failure_reason)`, `findings(id PK, filing_id FK, category, severity, rule_id, matched_text, excerpt, position)`, `processed_events(event_id PK, processed_at)` |

Schemas are created with Flyway (`V1__init.sql`). **Migrations are pinned in Wave 0** so agents cannot diverge.

---

## 8. Waves and agents

### Principles of parallel work
1. **One folder, one owner.** An agent writes only in its own folder. For another folder it asks the orchestrator for a change.
2. **The contract is locked** (section 4). Every agent reads it, none changes it.
3. **Separate branches and separate working copies** (git worktree), one branch per agent. Merging is done by the orchestrator.
4. **Each agent uses its own database and its own port for local runs.** Integration tests use Testcontainers with dynamic ports.
5. **Each agent leaves green tests** and a short "done / known issues" note in `docs/handoff/<agent>.md`.
6. **The acceptance test runs on the merged result**, not on separate branches.
7. Quiet build: `mvn -B -q verify > build.log 2>&1`, then read only the needed lines of the log.
8. **Every agent prompt includes the AI recording rules (section 12).**

### Wave 0 — Foundations (orchestrator, sequential, ~1 session)

| Task | Result |
|---|---|
| 0.1 Repo, structure, parent pom with all versions, Maven wrapper, .gitignore, .dockerignore; check the latest GA Spring Boot 4.x and Spring AMQP JSON converter | section 5 done |
| 0.2 Contract: JSON Schemas, OpenAPI, examples, topology document | `docs/contracts/` |
| 0.2b `common-contracts` module: records, enums, topology constants, event-id helper, contract test against the schemas and examples | module built and tested |
| 0.3 V1 migrations for Ingestion and Reporting | pinned SQL files |
| 0.4 Skeletons of 3 services depending on `common-contracts`: start, have health, one empty test, the ArchUnit layering test | `mvn verify` green |
| 0.5 `docker-compose.yml` with RabbitMQ and 3 services (no logic) | everything starts |
| 0.6 Agent rules (principles above) as `docs/AGENT-RULES.md` | |
| 0.7 AI process log: `docs/ai-process-log.md` (skeleton per 12.4), folders `docs/ai-conversations/` and `docs/handoff/`; `scripts/export-ai-conversation.py` per 12.2 | records start on day one |

**Gate:** the skeleton compiles and the contract is reviewed and approved.

### Wave 1 — Parallel (5 agents at the same time)

#### Agent A — Ingestion (`ingestion-service/`)
Tasks:
- A1 Domain and repositories (Filing, FilingStatus, OutboxEvent).
- A2 `FilingService`: validation (not empty, up to 2 MB, company and title present), write + outbox in one transaction.
- A3 `FilingController` + `GlobalExceptionHandler` per the OpenAPI.
- A4 `OutboxPublisher` (scheduled every 500 ms, configurable; publisher confirms; no loss on restart).
- A5 `AnalysisEventListener` (one queue) + `FilingStatusService` with transition checks, idempotency and the late-event rule.
- A6 Tests: unit tests for services and transitions (including a late `analysis.started` after `analysis.completed`); `@WebMvcTest` for the controller (`ProblemDetail`, `Location`); integration test with Testcontainers (submit -> event in the broker; a negative confirm keeps the row unpublished).

Acceptance: submit returns `202` with `Location`; the event is in the broker and matches the schema; a late transition is ignored, not rejected to the DLQ; a restart does not lose an unsent outbox row.

#### Agent B — Analysis (`analysis-service/`)
Tasks:
- B1 `risk-rules.yml`: at least 20 rules, 6 categories, with realistic 10-K phrases (litigation, material weakness, cybersecurity incident, going concern, supply chain disruption, regulatory investigation...).
- B2 `RuleLoader` + startup validation (an invalid regex means the service refuses to start with a clear error).
- B3 `RuleMatcher`, `ExcerptExtractor`, `RiskScorer`, `RiskAnalyzer` — pure logic; findings capped per rule.
- B4 `FilingSubmittedListener` + `AnalysisEventPublisher`; the two failure paths from section 2 (unreadable -> DLQ at once; processing failure after retries -> `analysis.failed` + ack).
- B5 Tests: rich unit tests of the engine (empty text, no matches, many matches, overlaps, letter case, a 2 MB input within a time limit); listener test with a fake publisher; integration test with Testcontainers.

Acceptance: `samples/sample-10k-excerpt.txt` produces the expected findings; a poison message reaches `.dlq` without retries; a forced processing failure publishes `analysis.failed`; the result matches the schema.

#### Agent C — Reporting (`reporting-service/`)
Tasks:
- C1 Domain and repositories (Report, FindingEntity, ProcessedEvent).
- C2 `AnalysisResultListener` (one queue, completed and failed) + `IdempotencyGuard` + the late-event rule (the first terminal event wins).
- C3 `ReportService`: report persistence, summaries per category and severity.
- C4 `ReportController` per the OpenAPI (`404` until ready).
- C5 Tests: a redelivered event creates no duplicate; `failed` creates a report with status FAILED; out-of-order events; integration test with Testcontainers.

Acceptance: the report matches the `analysis-completed` example from `docs/contracts/examples/`; double delivery results in one report.

#### Agent D — Frontend (`frontend/`)
Tasks:
- D1 `index.html`: form (company, title, text or `.txt` upload), "Load sample" button.
- D2 `app.js` with Fetch: submit, periodic status polling (every 2 s, with a limit and a clear message on timeout). Flow: poll the status until `COMPLETED` or `FAILED`; after `COMPLETED`, poll the report until `404` becomes `200` (the two services are eventually consistent).
- D3 Report view: overall rating, findings table by category and severity, excerpts with the match highlighted, `FAILED` handling. Filing text is rendered only through `textContent` and DOM nodes (the highlight too), never `innerHTML` — the text is untrusted input.
- D4 List of recently submitted filings.
- D5 Manual check and screenshots at 390 px and wide screens; a mock server for development without the backend (`frontend/mock/`).

Acceptance: works against the mock server per the contract; no Node build required.

#### Agent E — Infrastructure (`infra/`, `docker-compose.yml`, `scripts/`)
Tasks:
- E1 One Dockerfile per service under `infra/docker/<service>.Dockerfile`, built with the repository root as context (multi-stage: `./mvnw -pl <service> -am package` with a BuildKit cache mount for `~/.m2` builds the service together with `common-contracts` -> Spring Boot layered jar on `eclipse-temurin:21-jre-alpine`, non-root user, `HEALTHCHECK` with busybox `wget -qO- http://localhost:<port>/actuator/health`). Dockerfiles live in `infra/` so no agent writes into another agent's service folder.
- E2 RabbitMQ service in Compose with management UI and health check. Topology is declared by the services themselves (see `messaging-topology.md`), so no definitions file is needed.
- E3 `infra/nginx/default.conf` — static files + proxy `/api/filings`, `/api/reports`; `client_max_body_size 3m` (the 1 MB default would reject a 2 MB filing with `413`).
- E4 `docker-compose.yml`: health checks, `depends_on` with `service_healthy`, volumes for the databases, network, `.env.example`; only 8080 and 15672 published to the host.
- E5 `scripts/smoke.sh` (bash + curl only, no `jq`): submits `demo-filing.json`, waits for `COMPLETED`, checks the report, also submits a ~2 MB filing through nginx; exits non-zero on failure.
- E6 `scripts/chaos.sh`: stops Analysis, submits a filing, starts Analysis, verifies the filing is processed.
- E7 `.github/workflows/ci.yml`, two jobs on every push: `./mvnw -B verify`, then `docker compose up --build -d` + `scripts/smoke.sh` + `docker compose down`.

Acceptance: `docker compose up --build` on a clean machine; `smoke.sh` passes.

> Agent E depends only on the Dockerfile paths and the ports from section 3 — it does not wait for A–D.

### Wave 2 — Integration (orchestrator + 2 agents in parallel)

| Agent | Tasks |
|---|---|
| **F — Integration and resilience** | Merges branches A–E. Runs the whole system. Writes the `e2e` test (Testcontainers + Compose, or `smoke.sh`). Verifies the scenarios: Analysis down, Reporting down, duplicate delivery, poison message, Ingestion restart before the outbox is flushed. Fixes mismatches (within the contract). |
| **G — Documentation** | `README.md` (section 10), `docs/architecture.md` with diagrams (Mermaid), ADR files, "AI tools" section, layout of `docs/ai-conversations/`. Starts during Wave 1 (understands the architecture from this plan) and finishes after integration. |

### Wave 3 — Review and delivery (orchestrator)

| Task | Result | Status |
|---|---|---|
| 3.1 Independent code review (one agent only reads and writes remarks; does not fix) | list of issues | done: W3-01 to W3-09 |
| 3.2 Fix the remarks | | done: Wave 3 commit |
| 3.3 Clean clone: `docker compose up --build` + `smoke.sh` on a fresh checkout | confirmed | done: fresh clone, build also without the layer cache, smoke and chaos 6/6 |
| 3.4 README review against the checklist (section 11) | | done: section 11 ticked where it can be checked before publishing; final review added `docs/MANUAL-TESTING.md` and two tests for coverage gaps |
| 3.5 Conversation records ordered; denylist check and secret check (12.2) | records contain only this task | done: records 01-04; 03 re-exported with the end of its session; 04 is the final review session |
| 3.6 Publish to GitHub and open the link in a logged-out browser | URL for the reply email | done: pushed with the author's approval; public, clones without credentials; https://github.com/vladimirVuchkov/veritrade-risk-analysis |

---

## 9. Dependency map

```
        Wave 0 (skeleton + contract)
                 |
   +-----+-------+-------+-----+
   v     v       v       v     v
   A     B       C       D     E        <- Wave 1, all in parallel
   +-----+-------+-------+-----+
                 v
          F (integration)   G (documentation, starts earlier)
                 v
          Wave 3 (review and delivery)
```

| Agent | Reads | Writes | Blocked by |
|---|---|---|---|
| A | contract, `common-contracts`, migrations | `ingestion-service/` | W0 |
| B | contract, `common-contracts` | `analysis-service/` | W0 |
| C | contract, `common-contracts`, migrations | `reporting-service/` | W0 |
| D | OpenAPI | `frontend/` | W0 |
| E | ports, service names | `infra/`, `scripts/`, `docker-compose.yml` | W0 |
| F | everything | small fixes in other folders with orchestrator permission | A–E |
| G | everything | `README.md`, `docs/` (not `contracts/`) | W0 (finishes after F) |

---

## 10. README content (required)

1. What the system is (2–3 sentences) and the architecture diagram.
2. Quick start: prerequisites, `docker compose up --build`, addresses (UI, RabbitMQ management), example submission.
3. How to run the tests (`mvn verify`, smoke, chaos).
4. **Decisions and trade-offs:** why asynchronous (and its cost), why separate databases, why a shared contracts module (and where its boundary is), why outbox with publisher confirms, why one queue per consuming service, why rules in YAML, choreography vs orchestration (choreography is chosen, and why). Links to the ADR files in `docs/decisions/`.
5. **Resilience:** what happens when each service goes down; idempotency; late and conflicting events; retries, the two failure paths and DLQ; limitations (a poison `filing.submitted` leaves the filing `SUBMITTED`).
6. **What I would do for production:** PostgreSQL, clustered broker (or Kafka), authentication, saga/compensation, a stale-filing sweeper, tracing (OpenTelemetry), metrics, scaling Analysis through competing consumers, claim check for large filings (text in object storage, the event carries a reference).
7. **AI tools:** which were used and for what (section 12).
8. Known limitations.

---

## 11. Acceptance checklist before sending

- [x] The repository is public and clones without errors
- [x] `docker compose up --build` works from scratch with no manual steps
- [x] `scripts/smoke.sh` passes
- [x] `scripts/chaos.sh` passes
- [x] `mvn verify` is green for all three services
- [ ] The UI shows a report and a failure (`FAILED`) — checked through the API and the frontend tests; look at it in a browser with `docs/MANUAL-TESTING.md` step 4
- [x] README covers section 10
- [x] `docs/ai-conversations/` contains the records, only about this task, with no secrets, personal data, or keys (denylist check passes)
- [ ] The CI workflow is green (build and compose smoke job)
- [ ] `docs/ai-process-log.md` is filled in per the template (12.3–12.4) for all waves and agents — everything except the author's fields ("My intervention", "Decision", "My contribution", the conclusion)
- [x] README has an "AI tools" section linking to the log and the records
- [ ] No unused code, magic numbers, or copied blocks
- [ ] I can explain every class and every decision

---

## 12. AI tools and conversation records

### 12.1 What the assignment requires (from the brief)
1. Documentation, libraries, and AI assistants may be used freely.
2. **The README must contain a section describing which AI tools were used and for which parts of the task.**
3. **The conversation logs/exports with the AI assistants are committed to the repository**, to show the problem-solving process.
4. In the follow-up interview I must be able to explain any part of the code, the design decisions, and the trade-offs against alternatives.

### 12.2 Rules for keeping the records (orchestrator and all agents)
- Recording starts **on day one**, not at the end.
- **Dedicated sessions:** every session for this project starts fresh, with the repository as the working directory, and covers only this project. Anything unrelated is handled in a different session.
- **Raw record:** each session (orchestrator and every agent) is exported to `docs/ai-conversations/NN-<agent>-<topic>.md` (e.g. `01-orchestrator-planning.md`, `04-agent-analysis-rule-engine.md`). Numbers grow chronologically.
- **Export tool** `scripts/export-ai-conversation.py` (Python 3, standard library only): reads a session transcript (and its subagent transcripts) and keeps user messages, visible assistant text, tool calls (tool name and main argument) and tool results trimmed to a few lines. It drops injected system context (reminders, hook output, session-start context, memory) and hidden reasoning, replaces the home path with `~` and e-mail addresses with `[REDACTED]`.
- **Denylist check:** the export tool takes a denylist file that lives outside the repository (unrelated names, hosts, personal data). Any hit fails the export, and the exchange is reviewed by hand. The denylist is never committed.
- **Process log:** `docs/ai-process-log.md` is extended after every significant step (template below). It is the readable account; the raw records are the evidence.
- **Mandatory last lines of every `docs/handoff/<agent>.md`:** a link to the raw session record and 3–5 lines "what the agent was asked, what it produced, what I corrected manually".
- Before every commit each record is checked for secrets (tokens, passwords, keys, personal paths and data). Secrets are replaced with `[REDACTED]` without changing the meaning.
- Records are **not edited afterwards** to look better; the wording is never changed. Only two edits are allowed: secret or personal-data redaction (`[REDACTED]`), and removal of a whole exchange unrelated to this project, replaced by the marker `[unrelated exchange removed]`.
- To avoid merge conflicts, agents do not edit `docs/ai-process-log.md`. Each agent writes its entry (template below) at the end of its own `docs/handoff/<agent>.md`; the orchestrator compiles the entries into the log and writes the overall summaries.

### 12.3 Template for an entry in `docs/ai-process-log.md`

```
### [No.] Date — Step/Wave — Agent
- **Goal:** what had to be done
- **Tool and model:** e.g. Claude Code (Sonnet 5.5)
- **Given to the AI:** short description of the prompt/context (link to the raw record)
- **Received:** what it created or proposed
- **My intervention:** what I checked, rejected, fixed, or reworked, and why
- **Decision:** what we accepted; if there were alternatives, which and why rejected
- **Verification:** how it was confirmed (tests, manual check, review)
- **Problems/lessons:** what did not work the first time
```

### 12.4 Structure of the log
1. **Introduction:** tools and models used; which tool was used for which part of the task (feeds the README section).
2. **Chronology** by wave (W0 -> W3) with entries per the template.
3. **Table "System part -> AI contribution -> my contribution"** (Ingestion, Analysis, Reporting, Frontend, Infrastructure, Tests, Documentation).
4. **Organization of parallel work:** how many agents, which roles, how folders were split, which conflicts appeared and how they were resolved.
5. **What worked well with AI / what did not** (prompts that worked, AI mistakes caught in review).
6. **Conclusion:** overall lessons and what I would do differently.

### 12.5 Use in the README
The README "AI tools" section is a short extract of the log (tool -> what it was used for), linking to `docs/ai-process-log.md` and `docs/ai-conversations/`.

### 12.6 Checkpoints
| When | What is done with the records |
|---|---|
| End of Wave 0 | export the orchestrator session; first log entries |
| End of each Wave 1 agent | raw record + log entry + handoff note |
| End of Wave 2 | orchestrator merges the log, fills the table from 12.4 |
| Wave 3 | secret check; README section generated from the log |

---

## 13. Risks

| Risk | Mitigation |
|---|---|
| Agents drift from the contract | one frozen `common-contracts` module + contract test against the schemas and examples |
| The shared module grows into a dumping ground | strict rule: contract types only; orchestrator owns it; reviewed in Wave 3 |
| Merge conflicts | one folder, one owner; merging only by the orchestrator |
| Testcontainers does not work without Docker | check in Wave 0; fallback: Compose-based integration tests in `scripts/` |
| Not enough time for outbox | documented as a trade-off in the README; A4 is last in priority |
| Scope creep | everything outside section 8 is "for production" in the README and is not coded |
| Unrelated content or personal data leaks into the conversation records | dedicated sessions; the export tool strips injected context; denylist check at every export and in Wave 3 |
| Version drift with Spring Boot 4 / Jackson 3 (newer APIs, class renames) | exact versions and class names checked in Wave 0 before the agents start; plain records without Jackson annotations |

---

## 14. Proposed order after approval

1. Review and approval of this plan.
2. Wave 0 (orchestrator).
3. Parallel start of agents A–E (and G for documentation).
4. Wave 2, then Wave 3.
5. Publishing.

## 15. Progress

| Wave | State | Commit on `main` | Checks after the commit |
|---|---|---|---|
| 0 - Foundations | done, pushed | `5d3faef` and earlier | `mvnw verify`, Compose up |
| 0 - Conversation record | done, local | `5aa2ec2` | denylist check |
| 1 - Parallel implementation (A-E, G) | done, local | `aa5bd3c` | `mvnw verify`, frontend, Compose, smoke, chaos 6/6 |
| 2 - Integration (F, G) | done, local | `0201723` | plus E2E 132/132, export tool tests |
| 3.1-3.2 - Review and fixes (A, B, D, E, G) | done, local | Wave 3 commit | `mvnw verify` 840, frontend 218, export tool 7, E2E 144, smoke, chaos 6/6 |
| 3.3 - Clean clone | done | | fresh clone: `compose up --build` (also `--no-cache`), smoke, chaos 6/6 |
| 3.4 - README against section 11 and final review | done, local | final review commit | `mvnw verify` 842, frontend 218, export tool 7, E2E 147 (after the CI fix); `docs/MANUAL-TESTING.md` checked against a running stack |
| 3.5 - Conversation records | done, local: 01-04 | records commit | denylist and secret check |
| 3.6 - Publishing | done: pushed with approval, repository public | CI fix commit | first CI run: E2E failed on a stale nginx port in the test client; fixed, `FrontendRestartE2E` added |

Open items carried forward: a replay tool for parked outbox rows (known limitation, see README); the
"My intervention" and "Decision" fields of the process log are filled in by the author.

