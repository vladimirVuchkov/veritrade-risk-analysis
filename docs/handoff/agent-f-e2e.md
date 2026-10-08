# Handoff - Agent F (Integration and resilience, end-to-end suite)

## Done
- New Maven module `e2e-tests/`, built only with the profile `e2e`:
  `./mvnw -B -Pe2e verify -pl e2e-tests -am`. A plain `./mvnw -B verify` does not build it.
  - The suite starts the repository's `docker-compose.yml` once per run with `docker compose up --build
    --wait`, under its own project name (`veritrade-e2e-<random>`) and with both published ports set to
    `0` (random host ports). It never collides with a `veritrade` stack on 8080/15672, and it is removed
    with `down -v` at the end of the run.
  - It uses the docker compose CLI rather than Testcontainers `ComposeContainer`, because the scenarios
    stop and start single services, read their logs and look up a port again after a broker restart.
  - The tests talk to the stack only through nginx and the RabbitMQ management API. Waits use Awaitility;
    every timeout is a named constant in `Timeouts`.
  - REST bodies are validated against `rest-api.openapi.yaml` and every event the tests publish as a
    valid event is validated against its JSON Schema (networknt, as in the services).
  - `-De2e.project=NAME -De2e.keepStack=true` reuses a named stack and leaves it running (debugging, CI log dump).
- `FrontendFlowE2E` runs `e2e-tests/src/test/node/frontend-flow.test.mjs` with `node --test`. It imports
  the unchanged `frontend/js/api.js`, `polling.js` and `config.js`, points them at nginx and drives
  submit, status polling and the report, plus a 400 problem, a 404 report and the recent list.
- Parent `pom.xml`: only the `e2e` profile with the module. No version or dependency changes.
- `.github/workflows/ci.yml`: new job `e2e-suite` (after `build`) runs the module, dumps the logs on
  failure and always runs `down -v`.
- `scripts/chaos.sh`: scenario (b) now retries its 503 check (it failed once on the nginx stale upstream
  described below); nothing else in `scripts/` changed.
- Three service bugs found by the suite and fixed (below), each with a regression test in the service.
- Wave 3 follow-up: `LargeFilingE2E` sends filings of exactly 2,097,152 UTF-8 bytes through the whole
  system (ASCII and multibyte padding) and checks that the last phrase is found (timeout
  `Timeouts.LARGE_FILING_PROCESSING`). Before, only the 202 at the limit was tested.

## Scenarios
Each scenario is a separate test (or parameterized test) in `e2e-tests/src/test/java/com/veritrade/e2e/`.
Fresh filing ids isolate the tests; outage tests bring every service back afterwards. Results are from
the runs listed under "How to verify" (all passed).

| # | Scenario | Test (class.method) | Result |
|---|---|---|---|
| 1 | Happy path, SUBMITTED -> ANALYZING -> COMPLETED, consistent report in < 10 s | `HappyPathE2E.sampleFilingIsAnalysedAndReportedWithinTenSeconds` | pass |
| 2 | Zero findings: COMPLETED, NONE, empty findings | `HappyPathE2E.filingWithoutRiskTextCompletesWithNoFindings` | pass |
| 3 | Blank or missing fields | `InputValidationE2E.blankOrMissingFieldsAreRejectedWithOneErrorPerField` (4), `eachMissingFieldIsRejected` (3) | pass |
| 3 | Malformed JSON | `InputValidationE2E.malformedJsonIsABadRequestProblem` (6) | pass |
| 3 | Wrong or missing content type (415 problem+json) | `wrongContentTypeIsAnUnsupportedMediaTypeProblem` (3), `missingContentTypeIsAnUnsupportedMediaTypeProblem` | pass |
| 3 | Exactly 2 MB -> 202, 2 MB + 1 byte -> 400 (not 413) | `contentOfExactlyTwoMegabytesIsAccepted`, `contentOfTwoMegabytesPlusOneByteIsABadRequestNotPayloadTooLarge` | pass |
| 3 | Multibyte content at the limit | `twoByteCharactersAtTheByteLimitAreAcceptedAndOneMoreByteIsNot`, `emojiAtTheByteLimitAreAcceptedAndOneMoreByteIsNot` | pass |
| 3 | Company and title at max and max + 1 (UTF-16 units, emoji) | `companyNameAtTheMaximumLengthIsAcceptedAndOneMoreIsNot`, `companyNameLengthCountsUtf16UnitsSoEmojiCountTwice`, `titleAtTheMaximumLengthIsAcceptedAndOneMoreIsNot`, `titleLengthCountsUtf16UnitsSoEmojiCountTwice`, `surroundingWhitespaceIsStrippedBeforeTheLengthCheck` | pass |
| 3 | Body over the nginx 3 MB limit -> 413 problem+json | `bodyOverTheNginxLimitIsAPayloadTooLargeProblem` | pass |
| 4 | Unknown id -> 404 | `QueryEdgeCasesE2E.unknownFilingIdIsANotFoundProblem`, `unknownReportIdIsANotFoundProblem` | pass |
| 4 | Malformed id: 400 for filings, 404 for reports (as the code does) | `malformedIdIsABadRequestForFilingsAndANotFoundForReports` (3) | pass |
| 4 | `limit` default, empty, 1, 100, 101, 0, negative, non-numeric | `listWithoutLimitReturnsTheDefaultTwenty`, `emptyLimitIsTreatedAsNoLimit`, `limitOfOneReturnsOneFiling`, `limitOfOneHundredIsAccepted`, `limitOutsideOneToOneHundredOrNotAnIntegerIsABadRequestProblem` (6) | pass |
| 4 | Newest first | `listIsNewestFirst` | pass |
| 5 | Correlation id returned and in the logs of nginx and all three services | `CorrelationIdE2E.correlationIdIsReturnedAndLoggedByNginxAndAllThreeServices` (+ generated, too long, at limit, non-ASCII, not a strict token, on errors: 7 test methods) | pass |
| 6 | Analysis down: stays SUBMITTED, completes after restart | `ServiceOutageE2E.filingStaysSubmittedWhileAnalysisIsDownAndCompletesAfterItRestarts` | pass |
| 7 | Reporting down: COMPLETED, report 503 problem+json, report after restart | `ServiceOutageE2E.reportIsUnavailableWhileReportingIsDownAndAppearsAfterItRestarts` | pass |
| 8 | Ingestion down while analysis events are produced | `ServiceOutageE2E.analysisEventsWaitWhileIngestionIsDownAndTheStatusCatchesUpAfterItRestarts` | pass |
| 9 | Broker stopped: outbox row stays unpublished, filing completes after broker start | `ServiceOutageE2E.outboxKeepsTheEventWhileTheBrokerIsDownAndTheFilingCompletesAfterTheBrokerRestarts` | pass |
| 9 | Ingestion restarted while the broker is down | `ServiceOutageE2E.unpublishedOutboxEventSurvivesAnIngestionRestartWhileTheBrokerIsDown` | pass |
| 10 | Duplicate `analysis.completed` | `ControlledAnalysisEventsE2E.duplicateAnalysisCompletedGivesOneReportWithoutDuplicateFindings` | pass |
| 10 | Duplicate `analysis.failed` | `ControlledAnalysisEventsE2E.duplicateAnalysisFailedGivesOneFailedReport` | pass |
| 10 | Redelivered `filing.submitted` | `FilingSubmittedConsumerE2E.redeliveredFilingSubmittedIsAnalysedAgainAndChangesNothing` | pass |
| 11 | `analysis.completed` before `analysis.started` | `ControlledAnalysisEventsE2E.analysisCompletedBeforeAnalysisStartedEndsCompleted` | pass |
| 11 | `analysis.failed` after COMPLETED | `ControlledAnalysisEventsE2E.analysisFailedAfterCompletedIsIgnoredByIngestionAndReporting` | pass |
| 11 | `analysis.completed` after FAILED (both services agree) | `ControlledAnalysisEventsE2E.analysisCompletedAfterFailedIsIgnoredAndBothServicesAgreeOnFailed` | pass |
| 11 | Real results arriving late after Analysis restarts change nothing | `ControlledAnalysisEventsE2E.realAnalysisResultsArrivingAfterTheRestartChangeNothing` | pass |
| 12 | Failure path end to end (published through the management API) | `ControlledAnalysisEventsE2E.analysisFailureReachesFailedWithTheReasonInIngestionAndReporting` | pass |
| 13 | Poison messages to each work queue -> matching `.dlq`, no retries, valid filings still flow | `PoisonMessageE2E.poisonGoesStraightToTheMatchingDeadLetterQueueAndValidFilingsStillFlow` (4 routing keys x 4 poisons), `analysisEventForAnUnknownFilingIsDeadLetteredByIngestion` | pass |
| 14 | `__TypeId__` (real and bogus) on Ingestion and Reporting | `ControlledAnalysisEventsE2E.typeHeaderIsIgnoredByIngestionAndReporting` (4) | pass |
| 14 | `__TypeId__` (real and bogus) on Analysis | `FilingSubmittedConsumerE2E.typeHeaderOnFilingSubmittedIsIgnoredByAnalysis` (4) | pass after fix 1 |
| 15 | Unknown fields | `ControlledAnalysisEventsE2E.unknownFieldsAtEveryLevelAreIgnored`, `FilingSubmittedConsumerE2E.unknownFieldsInFilingSubmittedAreIgnoredByAnalysis` | pass |
| 15 | Higher `eventVersion` goes to the DLQ in every consumer, without retries | `ControlledAnalysisEventsE2E.higherEventVersionIsDeadLetteredByIngestionAndReporting`, `FilingSubmittedConsumerE2E.higherEventVersionOfFilingSubmittedIsDeadLetteredWithoutRetries` | pass |
| 15 | Failure reason one UTF-16 unit over 1000 is dead-lettered by Ingestion, not cut | `ControlledAnalysisEventsE2E.failureReasonOverTheUtf16LimitIsDeadLetteredByIngestion` | pass |
| 15 | Text limits in UTF-16 units in Reporting | `ControlledAnalysisEventsE2E.reportingAcceptsMatchedTextAtTheUtf16LimitAndStoresItIntact`, `reportingDeadLettersMatchedTextOneUtf16UnitOverTheLimit` (2) | pass after fix 2 |
| 16 | 30 parallel filings, one consistent report each, empty DLQs | `ConcurrencyE2E.parallelFilingsAllCompleteWithOneConsistentReportEachAndNothingDeadLettered` | pass |
| 16 | Content of exactly 2,097,152 UTF-8 bytes through nginx to a COMPLETED report: risk phrases at the start, the middle and the last bytes all found (end position checked), nothing dead-lettered; ASCII and multibyte (Cyrillic + emoji) padding | `LargeFilingE2E.asciiFilingOfExactlyTwoMegabytesIsAnalysedUpToItsLastByte`, `LargeFilingE2E.multibyteFilingOfExactlyTwoMegabytesIsAnalysedUpToItsLastByte` | pass (about 2 s each) |
| - | W3-03: a hard-wrapped filing gives the same findings as the unwrapped one | `HardWrappedFilingE2E.hardWrappedSampleGivesTheSameRisksAsTheUnwrappedSample` | pass |
| 17 | UI: MIME types, hidden files, security headers | `StaticUiE2E` (11 test methods, 27 tests) | pass |
| 17 | W3-04: UI and management UI published on loopback only; broker warns about a default password | `PublishedPortsE2E` (3 test methods) | pass |
| 18 | Topology: exchanges, queues, DLQs, bindings, arguments, consumers with prefetch 10 | `TopologyE2E` (7 test methods, 11 tests) | pass |
| - | Frontend modules against the real stack | `FrontendFlowE2E.frontendModulesDriveSubmitPollAndReportAgainstTheRealStack` (4 Node tests) | pass |

## Service bugs found and fixed
1. **Analysis converted every message body in the listener container (W3-02).** `RabbitConfig` declared
   a `MessageConverter` bean; Spring Boot also gives it to the listener container, so the body was
   converted (by its `__TypeId__` header) before `FilingSubmittedListener` ran.
   - Effect, proven end to end: a valid `filing.submitted` with
     `__TypeId__: com.veritrade.contracts.event.EventEnvelope` ended as a FAILED report with
     "Analysis failed after all retries: IllegalArgumentException: The class ... is not in the trusted
     packages". Unreadable JSON was retried 3 times before it reached the DLQ.
   - Fix: the converter is set on the `RabbitTemplate` only, through a `RabbitTemplateCustomizer` (as in
     Ingestion); `AnalysisEventPublisher` uses the template's converter.
   - Regression tests: `AnalysisFlowIT.ignoresAJavaTypeHeaderOnAValidFiling` (3 header values),
     `AnalysisFlowIT.theListenerContainerGetsNoJsonConverter`, and the two "WithoutRetries" ITs, which now
     verify that the listener was invoked exactly once (for unreadable JSON the old code never invoked
     it). Run against the old converter bean, 5 of these AnalysisFlowIT cases fail. Unit test:
     `RabbitConfigTest.putsTheJsonConverterOnTheTemplateOnly`.
   - E2E: `FilingSubmittedConsumerE2E.typeHeaderOnFilingSubmittedIsIgnoredByAnalysis`,
     `PoisonMessageE2E` (`filing.submitted` cases).
2. **Reporting counted text limits in code points (W3-06).** The contract counts UTF-16 code units, so
   250 emoji + 1 character (501 units) passed as `matchedText` and was cut to 500 units silently.
   - Fix: `AnalysisEventValidator` compares `String.length()`; comments in `ReportingLimits`,
     `application.yml` and `ColumnText` updated.
   - Regression tests: `AnalysisEventReaderTest.acceptsEmojiTextExactlyAtTheLimitsInUtf16Units` and
     `rejectsEmojiTextOverTheLimitInUtf16UnitsAlthoughWithinItInCodePoints` (fails on the old code).
   - E2E: `ControlledAnalysisEventsE2E.reportingDeadLettersMatchedTextOneUtf16UnitOverTheLimit[emoji]`.
3. **A shutdown during a broker outage was killed, and H2 lost committed filings.** While the
   `rabbitmq` container is stopped, a connection attempt to its old address hangs for the default 60 s
   connection timeout. Stopping Ingestion in that state took longer than Docker's 10 s grace period
   (the listener container, the health check and the outbox publisher each wait on a connection), so
   the container was killed (exit 137).
   - Effect, seen in an E2E run of `unpublishedOutboxEventSurvivesAnIngestionRestartWhileTheBrokerIsDown`:
     after the forced kill H2 came back without the last minute of commits; filings that had been
     accepted with 202 were 404 and status changes were rolled back. The kill reproduces every time
     (manual repro: exit 137 after 10 s); the data loss itself is not deterministic (a manual repro was
     killed the same way and kept its data).
   - Fix: `spring.rabbitmq.connection-timeout: 2s` in Ingestion and Reporting (the two services with H2).
     With it the same stop is graceful (exit 143 after 4 s).
   - Regression tests: `BrokerConnectionTimeoutTest` in both services (fails without the property).
   - E2E: the restart-while-broker-down scenario now asserts that the accepted filing is still there.
   - H2 losing committed data on SIGKILL remains a limitation of the proof of concept (PostgreSQL in
     production); a `docker kill` or an out-of-memory kill can still lose recent rows.

## Known issues and limitations
- **Requests raised in Wave 2, all resolved in Wave 3** (the tests now pin the resolved behaviour):
  - Agent E: nginx keeps an upstream address for 5 s (`resolver ... valid=5s`) and has
    `proxy_connect_timeout 2s`, so a stopped service gives a fast 503 (`Timeouts.UPSTREAM_UNAVAILABLE`,
    `Timeouts.UPSTREAM_ADDRESS_CACHE`).
  - Agent B: Analysis sets `spring.rabbitmq.connection-timeout: 2s` as well.
  - Orchestrator: an unsupported `eventVersion` goes to the DLQ in every consumer without retries
    (`higherEventVersionIsDeadLetteredByIngestionAndReporting`,
    `higherEventVersionOfFilingSubmittedIsDeadLetteredWithoutRetries`); `maxLength` counts UTF-16 units
    and the contract notes that JSON Schema validators count code points; Analysis cuts a failure reason
    to 1000 UTF-16 units and Ingestion dead-letters a longer one
    (`failureReasonOverTheUtf16LimitIsDeadLetteredByIngestion`).
- The Wave 3 findings W3-xx other than W3-02 and W3-06 were not touched, as agreed.
- Content of 2 MB of non-ASCII text sent with `\uXXXX` JSON escapes is larger than nginx's 3 MB limit
  and gets 413. Raw UTF-8 (what the UI and `JSON.stringify` send) is accepted; the tests use raw UTF-8.
- Test classes run one after the other and share one stack; classes that stop services bring them back
  in `@AfterEach`/`@AfterAll`. Running the suite in parallel is not supported.
- The suite reads the management credentials from `RABBITMQ_USERNAME`/`RABBITMQ_PASSWORD` in the
  environment (default `veritrade`). With other values in `.env`, export them before the run.
- A full run takes about 4 minutes on colima with a warm image cache (images are built by `up --build`).

## How to verify
- `./mvnw -B -Pe2e verify -pl e2e-tests -am > e2e.log 2>&1; tail -n 40 e2e.log` (Docker with docker
  compose v2 and Node 22 on the PATH; no Testcontainers socket override is needed, the suite uses the
  docker compose CLI). Reports: `e2e-tests/target/failsafe-reports`.
  - Results on this machine (colima): the first full run found fix 3 (one error in
    `ServiceOutageE2E`); after the fix two consecutive full runs passed, 132 tests each, in 3:58 and
    3:47 (ServiceOutageE2E about 100 s, ControlledAnalysisEventsE2E about 61 s, the rest under 21 s each).
  - After the Wave 3 follow-up (`LargeFilingE2E`): 146 tests in 15 classes, all passed, 4:07
    (`LargeFilingE2E` 4.5 s). With an absent rule id (`MKT-004` in place of `REG-004`) expected,
    both `LargeFilingE2E` tests failed; the change was reverted.
- `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./mvnw -B verify`: green, 663 tests
  (654 before plus the new regression tests). One earlier attempt failed with "Could not connect to
  Ryuk" in Reporting's ITs (a colima port-forward hiccup); the rerun passed.
- `node --test frontend/test/`: 188 pass.
- `docker compose up --build -d --wait`, `scripts/smoke.sh`: passed (report after 672 ms).
- `scripts/chaos.sh`: all six scenarios passed after the scenario (b) retry; `docker compose down -v`.

## AI record
- Raw record: [`03-orchestrator-waves-1-3.md`, subagent transcript `agent-aa068b7b41a4a1a79`](../ai-conversations/03-orchestrator-waves-1-3.md#subagent-transcript-agent-aa068b7b41a4a1a79)
  (Wave 2); the `LargeFilingE2E` follow-up: [`04-orchestrator-final-review.md`, subagent transcript `agent-aa63d158b7951d2d1`](../ai-conversations/04-orchestrator-final-review.md#subagent-transcript-agent-aa63d158b7951d2d1)
- Asked for: Wave 2 Agent F: an `e2e-tests` module under a Maven profile that runs every scenario of
  the whole system against the real Compose stack (through nginx and the management API only), a Node
  run of the frontend flow, a CI job, fixes for service bugs the tests prove (W3-02, W3-06), and this note.
- Received: the module (132 tests in 12 classes plus a Node test file with 4 tests; 146 tests in 15
  classes after Wave 3 and the `LargeFilingE2E` follow-up), the CI job, two service
  fixes with regression tests, and the findings and requests listed above.
- Fixed by hand: nothing; the orchestrator decided the contract questions raised here (unsupported eventVersion goes to the DLQ in every consumer, Analysis cuts the failure reason to 1000 UTF-16 units) and scheduled the service changes for Wave 3
