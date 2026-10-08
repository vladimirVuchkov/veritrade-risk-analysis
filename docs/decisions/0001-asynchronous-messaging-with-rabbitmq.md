# 0001 - Asynchronous messaging with RabbitMQ

## Context
A filing goes through three steps: intake, analysis and reporting. Analysis can be slow, and any one
service may be down for a while. Users must still be able to submit filings, and a submitted filing
must never be lost.

## Decision
The services talk to each other only through events on RabbitMQ (topic exchange `veritrade.events`).
REST is used only for external calls: the browser calls Ingestion (`/api/filings`) and Reporting
(`/api/reports`) through nginx. Queues are durable and messages are persistent. Every producer uses
publisher confirms and `mandatory` publishing. Consumers acknowledge a message only after they have
processed it.

## Consequences
- Intake keeps working while Analysis or Reporting is down. Their messages wait in durable queues.
- The system is eventually consistent. The UI polls the filing status and then the report, and it
  accepts a short window in which the status is `COMPLETED` but the report still returns 404.
- Delivery is at-least-once, so every consumer must be idempotent (see
  [0009](0009-deterministic-event-ids.md)) and must tolerate late events (see
  [0008](0008-first-terminal-event-wins.md)).
- Tests need a real broker, so the integration tests start RabbitMQ with Testcontainers.
- Rejected alternative: synchronous REST calls between services. They are easier to trace, but one
  slow or stopped service would block or fail the whole request.
