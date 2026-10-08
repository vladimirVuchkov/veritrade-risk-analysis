# Handoff - Agent B (Analysis)

## Done
- **B1 rules:** `analysis-service/src/main/resources/risk-rules.yml`, `rulesVersion: "1.0"`, 33 rules in all
  6 categories and all 4 severities, written as realistic 10-K phrases (going concern, material weakness,
  covenant default, pending litigation, class action, supply chain disruption, cybersecurity incident,
  ransomware, data breach, regulatory/SEC investigation, Wells notice, FCPA, intense competition,
  economic downturn, and more). The rule ids match the contract example (`LEGAL-001`, `CYBER-001`, `FIN-002`).
- **B2 `RuleLoader`:** SnakeYAML with a safe constructor and duplicate YAML keys turned off. It checks the
  file shape, `rulesVersion` (a quoted string of 1 to 32 characters), the rule id format `^[A-Z]+-[0-9]{3}$`,
  duplicate ids, unknown categories, severities and keys, empty, blank or non-string patterns, invalid
  regexes, and patterns that match empty text. All problems go into one `RuleValidationException`, and
  `EngineConfig` fails, so the service does not start. A missing rules file also stops startup.
- **B3 engine (pure Java, no Spring):** `RuleMatcher`, `ExcerptExtractor`, `RiskScorer` and `RiskAnalyzer`,
  plus `RiskRule`, `RuleSet` and `Match`. The domain records are `Finding` and `AnalysisResult`.
  - Matching ignores case (`CASE_INSENSITIVE | UNICODE_CASE`). All patterns of one rule are merged: where
    two matches overlap, the one that starts earlier wins, and the longer one wins at the same start. Each
    rule keeps at most `max-matches-per-rule` matches (default 50), the earliest ones. Matches from
    different rules may overlap, because they are different findings.
  - `position` is the zero-based UTF-16 offset. A match is cut to `max-matched-text-chars` (500, the
    schema limit). Excerpts take ±`excerpt-context-chars` (default 120), are clamped at the text edges and
    never split a surrogate pair.
  - Findings are sorted by position, then by rule id, so the output is deterministic. `byCategory` leaves
    out empty categories.
  - Overall risk level: `NONE` when there are no findings. Otherwise it is the highest severity, raised
    one level when the number of findings is at least `escalation-threshold` (default 10). `CRITICAL`
    stays `CRITICAL`. Please describe this rule in the README.
  - Settings live in `RulesProperties` (`veritrade.analysis.rules.*`) and `MessagingProperties`
    (`veritrade.analysis.messaging.confirm-timeout`, default 5s).
- **B4 messaging:**
  - `FilingSubmittedListener` reads the raw AMQP message with `FilingSubmittedReader`, which is a tolerant
    reader: it ignores unknown fields and takes the type from `eventType`, never from a type header. It
    then publishes `analysis.started`, analyses the filing and publishes `analysis.completed`. The
    `correlationId` is in the MDC while the message is processed.
  - `AnalysisEventFactory` builds the envelopes with `EventIds.forFiling`, so ids are deterministic.
  - `AnalysisEventPublisher` sends to `veritrade.events` with these AMQP properties: messageId = eventId,
    contentType `application/json`, correlationId, and persistent delivery. It waits for the publisher
    confirm. A nack, an unroutable return or a timeout throws, and the listener retry then handles it.
  - Failure path (a): an invalid message throws `InvalidFilingMessageException`, which extends
    `AmqpRejectAndDontRequeueException`. A `RabbitListenerRetrySettingsCustomizer` excludes it from retry,
    and `FailedAnalysisRecoverer` rejects it, so it goes straight to `analysis.filing-submitted.dlq`.
  - Failure path (b): any other exception is retried. After the last of 3 attempts, `FailedAnalysisRecoverer`
    publishes `analysis.failed` with the reason and then acks the message. If even `analysis.failed`
    cannot be published, the message goes to the dead-letter queue, so it is not lost.
  - `RabbitConfig` declares exactly the documented topology: both exchanges, the work queue with only
    the two dead-letter arguments, the dead-letter queue and both bindings. It also defines the
    `JacksonJsonMessageConverter` built with the application `JsonMapper`.
- **B5 tests:** 183 unit tests and 12 integration tests, all green. See "How to verify".
- `application.yml`: the retry property is now `max-retries: 2`. See the first known issue.

## Known issues and limitations
- **Retry property in Spring Boot 4 (affects the other services):** Boot 4.1 has no
  `spring.rabbitmq.listener.simple.retry.max-attempts` any more. The property is `max-retries`, which
  counts retries after the first attempt, and its default is 3, which means 4 attempts. The old key is
  ignored without a warning. Analysis now uses `max-retries: 2`, which gives the 3 attempts of the
  contract, and `AnalysisFailureIT` checks this. `ingestion-service` and `reporting-service` still have
  `max-attempts: 3`. **Request to the orchestrator:** tell Agents A and C.
- **Testcontainers with colima:** Ryuk cannot mount the colima socket path
  (`~/.colima/default/docker.sock`: "operation not supported"), so the integration tests fail on this
  machine without an override. They pass with `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
  Linux CI does not need it. **Request to the orchestrator:** set this for the local environment, for
  example in the README or in the `~/.testcontainers.properties` of the machine.
- **RabbitMQ 4.3** does not accept transient non-exclusive queues, so the capture queue in the tests is
  durable. The management API shows `x-queue-type: classic` even though the service declares no such
  argument. `TopologyIT` allows for this, and `RabbitConfigTest` checks that the client declaration has
  exactly the two documented arguments.
- **Sample request:** a realistic sample filing is at
  `analysis-service/src/test/resources/samples/sample-10k-excerpt.txt`. It produces 26 findings in all 6
  categories, and the overall level is `CRITICAL`. The orchestrator should copy it to
  `samples/sample-10k-excerpt.txt`; Agent B may not write in `samples/`.
- An unreadable `filing.submitted` leaves the filing `SUBMITTED`. This is the documented limitation from
  the contract.
- If no queue is bound for an `analysis.*` event, the event is returned (mandatory publishing) and counts
  as a publish failure, so it is retried and finally dead-lettered. In practice the Ingestion queue is
  always bound before any filing exists.
- Each listener retry publishes `analysis.started` again with the same `eventId`. Consumers drop the
  duplicate by `eventId`.
- Matching is per regex over the full text in memory, which is fine for 2 MB: about 1 s on a laptop, and
  the test limit is 5 s. Very large filings would need the claim-check approach described in the plan.

## How to verify
- `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./mvnw -B -q -pl analysis-service -am verify > build.log 2>&1; tail -n 50 build.log`
  (the override is only needed on colima). An empty tail with exit code 0 means green.
- Unit tests (`*Test`, surefire, 183):
  - `RuleLoaderTest`: the bundled file and every validation failure.
  - `RuleMatcherTest`: empty and blank text, case, Unicode case, start and end of text, multibyte text,
    regex special characters, overlaps, the cap exactly and cap + 1, zero-length matches, long-match clipping.
  - `ExcerptExtractorTest`: edges, surrogate pairs, overflow.
  - `RiskScorerTest`: every severity, threshold - 1, threshold and threshold + 1, `CRITICAL` stays `CRITICAL`.
  - `RiskAnalyzerTest`: one match per category, the contract example reproduced exactly, the sample 10-K,
    `byCategory`, deterministic order, 2 MB within 5 s.
  - `FilingSubmittedListenerTest`: valid event, every missing field, unknown fields, wrong or unknown
    `eventType`, malformed JSON, processing failure, redelivery gives the same ids, MDC.
  - `FailedAnalysisRecovererTest`, `AnalysisEventPublisherTest` (properties, nack, return, timeout,
    interrupt) and `AnalysisEventFactoryTest` (schema-valid, equal to the contract examples).
  - `EngineConfigTest`: the service refuses to start with invalid rules, a missing file or an invalid limit.
  - `RabbitConfigTest` and `ArchitectureTest` (layering: engine and domain have no Spring, Jackson or
    broker dependency).
- Integration tests (`*IT`, failsafe, Testcontainers `rabbitmq:4.3-management-alpine`, 12):
  - `AnalysisFlowIT`: `filing.submitted` leads to `analysis.started` and `analysis.completed`, both valid
    against `docs/contracts` with the right AMQP properties. The contract example gives the contract
    result. Poison and invalid messages reach the `.dlq` once (`x-death` count 1, reader called once).
    A duplicate delivery produces the same event ids, and unknown fields are ignored.
  - `AnalysisFailureIT`: a forced analyzer failure runs 3 attempts, then `analysis.failed` is published
    and the message is acked (the work queue and the dead-letter queue are empty).
  - `TopologyIT`: queues, arguments, exchanges and bindings as seen through the management API.

## AI record
- Raw record: exported by the orchestrator from its session (subagent transcript)
- Asked for: Wave 1 Agent B, tasks B1 to B5 from `docs/PLAN.md`: the rules YAML, a validating
  `RuleLoader`, a pure rule engine, the listener and publisher with the two failure paths, a
  `RabbitConfig` with exactly the contract topology, an ArchUnit test, and complete unit and Testcontainers
  integration tests for every edge case.
- Received: the items above in `analysis-service/` on the branch `agent/analysis`, with a green
  `verify` (183 unit tests and 12 integration tests). Found along the way: the Boot 4 retry property
  rename, the colima and Ryuk socket problem, and the RabbitMQ 4.3 restriction on transient queues.
- Fixed by hand: nothing; the orchestrator copied the sample fixture to `samples/sample-10k-excerpt.txt` as requested
