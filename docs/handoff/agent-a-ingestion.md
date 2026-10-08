# Handoff - Agent A (Ingestion)

## Done
- A1 Domain (`Filing` with the status lifecycle in `changeStatus`, `FilingStatus`, `StatusChange`,
  `OutboxEvent`, `FilingView`) and repositories. Listing and status reads use a projection, so they
  do not load the filing content.
- A2 `FilingService` + `FilingValidator`: company name and title are required and stripped. Content
  must not be blank and must be at most 2,097,152 UTF-8 bytes. The filing and its `filing.submitted`
  outbox row are written in one transaction (`OutboxService.enqueue` is `Propagation.MANDATORY`).
- A3 `FilingController` (`/api/filings`), `GlobalExceptionHandler` (RFC 9457, `application/problem+json`,
  with an `errors` array for validation) and `CorrelationIdFilter`. The filter reads `X-Correlation-Id`,
  or generates one when the header is missing, blank or over 128 characters. It puts the id in the MDC
  and echoes it in the response. `POST` returns 202 with a relative `Location`. `GET` lists newest first:
  the limit defaults to 20, must be 1..100, and anything else is 400. An unknown id is 404, a malformed
  UUID is 400.
- A4 `OutboxPublisher`: runs on a fixed delay through `OutboxSchedulingConfig`, every 500 ms by default
  (`ingestion.outbox.publish-interval`). It sends rows in `created_at, id` order and marks a row
  published only after a positive confirm with no return. On a nack, a return, a timeout or a broker
  error it stops the run, so order is kept and the row is sent again on the next run.
- A5 `AnalysisEventListener` + `AnalysisEventReader` + `FilingStatusService`:
  - One queue. The listener dispatches on `eventType` and is a tolerant reader.
  - Duplicate events are no-ops (INFO log).
  - Late or contradictory events are acknowledged and logged at WARN with the eventId, the filingId
    and both states. They never throw.
  - Invalid events, unconvertible bodies and events for an unknown filing go straight to `.dlq`
    without retries (retry-settings customizer).
  - Other failures get 3 attempts, then `RejectAndDontRequeueRecoverer` sends them to `.dlq`.
- `RabbitConfig` declares exactly the documented topology. All limits are in `IngestionProperties`
  (`ingestion.*`).
- The `JacksonJsonMessageConverter` is set on the `RabbitTemplate` only, through a
  `RabbitTemplateCustomizer`, and is not a `MessageConverter` bean. Spring Boot gives a converter bean
  to the listener container as well.
  - This fixes a bug found in compose testing: the bean made the container convert each body by the
    producer's `__TypeId__` header. Analysis sends `EventEnvelope` in that header, the class is not
    trusted, so every analysis event was dead-lettered and filings stayed SUBMITTED.
  - The listener now gets the raw message and `AnalysisEventReader` dispatches on `eventType` only.
- `application.yml`: replaced `retry.max-attempts: 3` with `max-retries: 2`. In Boot 4.1 `max-attempts`
  no longer exists and is silently ignored, which would give 4 attempts. An IT asserts that there are
  exactly 3 attempts.
- A6 Tests: 259 unit tests (`*Test`) and 20 Testcontainers ITs (`*IT`). The ArchUnit layering test
  is included.

## Known issues and limitations
- **H2 2.4.240 bug (request to the orchestrator: pin `h2.version` to 2.3.232 in the parent pom).**
  A CHECK constraint (`ck_filings_status`) fails with "Check constraint invalid" on every insert once
  the JDBC connection that created it is closed. A minimal JDBC repro fails on 2.4.240 and passes on
  2.3.232.
  - In production this happens after the first start on an empty volume, once Hikari retires the
    connection Flyway used (max lifetime 30 min). From then on every `POST /api/filings` returns 500
    until the service restarts.
  - `RepositoryTest` uses the pooled data source for this reason. Reporting is probably affected too.
- **Contract mismatch (request to the orchestrator).** The OpenAPI/JSON Schema `maxLength` (200 / 300 /
  1000) counts code points, but the H2 `VARCHAR` columns count UTF-16 units. Ingestion validates in
  UTF-16 units, so every accepted value fits its column. As a result, a name of more than 100
  characters outside the BMP (for example emoji) gets 400 although the schema allows it. Failure
  reasons are cut to 1000 UTF-16 units without splitting a surrogate pair. To fix this, either state
  "UTF-16 units" in the contract or add a V2 migration that widens the columns.
- Only one instance may run the publisher (`ingestion.outbox.enabled=false` turns it off). Delivery is
  at-least-once: a lost confirm means the row is sent again with the same `messageId`.
- Until Analysis has declared `analysis.filing-submitted`, `filing.submitted` is unroutable. It is
  returned and stays in the outbox, then is sent once the queue exists, so nothing is lost.
- A `analysis.completed` with an unknown enum value (for example a new `RiskCategory`) fails to
  deserialize into the contract record and is dead-lettered. Adding an enum value is a breaking change.
- `?limit=` (empty) is treated as no limit (default 20).
- On a Mac with colima, run the ITs with `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
  RabbitMQ 4.3 refuses non-durable, non-exclusive queues, so test queues are durable and deleted
  after each test.

## How to verify
- `./mvnw -B -q -pl ingestion-service -am verify > build.log 2>&1; tail -n 50 build.log`
  (add `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` on colima). It is green:
  259 unit tests and 20 ITs.
- `OutboxPublishingIT`:
  - The event reaches the broker, matches `filing-submitted.schema.json` and the envelope schema,
    and carries the contract AMQP properties.
  - An unroutable event stays unpublished until a queue is bound.
  - A nack from a `reject-publish` queue keeps the row unpublished.
- `OutboxRestartIT`: a row that was not sent before shutdown is published after the restart (file H2).
- `AnalysisEventsIT`:
  - Status moves through the lifecycle.
  - Late, contradictory and duplicate events are acked and not dead-lettered.
  - Poison, unknown-type, missing-field and unknown-filing messages go to `.dlq` without retries.
  - A transient failure gets exactly 3 attempts, then `.dlq`.
  - Re-declaring with the contract arguments succeeds; any other arguments get `PRECONDITION_FAILED`.
  - The exact `docs/contracts/examples/analysis-*.json` bodies, sent with
    `__TypeId__: com.veritrade.contracts.event.EventEnvelope`, update the status. A bogus
    `__TypeId__: java.lang.Runtime` is ignored.
  - A Java-serialized body goes to `.dlq` and is never deserialized.
  - The listener container has no JSON payload converter and the context has no `MessageConverter`
    bean.

## AI record
- Raw record: exported by the orchestrator from its session (subagent transcript)
- Asked for: tasks A1-A6 for ingestion-service, following PLAN.md, AGENT-RULES.md and the frozen
  contract, with exhaustive unit, WebMvc and Testcontainers tests and a green verify.
- Received: the full service (domain, services, REST API with ProblemDetail, outbox publisher with
  confirms and returns, tolerant listener with the late-event rule, topology and retry configuration),
  279 tests, and two findings for the orchestrator (the H2 2.4.240 CHECK constraint bug, and code
  points vs UTF-16 units in the contract).
- Fixed by hand: orchestrator reordered two statements in `OutboxPublishingIT` (bind the recovery queue before removing the rejecting one) to remove a race seen in the merged build; the `__TypeId__` listener bug found by `chaos.sh` was sent back and fixed by the agent
