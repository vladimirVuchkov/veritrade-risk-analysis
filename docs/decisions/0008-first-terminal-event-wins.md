# 0008 - The first terminal event wins

## Context
With at-least-once delivery and retries, a consumer can see an event late, twice or out of order:
`analysis.started` after `analysis.completed`, or `analysis.failed` after `analysis.completed`.
Throwing an exception for such an event would retry it and then send a valid message to the
dead-letter queue.

## Decision
`COMPLETED` and `FAILED` are final, and the first terminal event wins.
- **Ingestion:** `Filing.changeStatus` applies only the allowed transitions
  (`SUBMITTED -> ANALYZING | COMPLETED | FAILED`, `ANALYZING -> COMPLETED | FAILED`). A change to the
  current status is a duplicate and is logged at INFO. Any other change is rejected and logged at
  WARN with the event id, the filing id and both states.
- **Reporting:** one report per filing. A known `eventId` is a duplicate. Any other terminal event for
  a filing that already has a report is logged at WARN and ignored.

In both services the message is acknowledged and nothing is thrown.

## Consequences
- Late and contradictory events never reach a dead-letter queue.
- A stored result is never overwritten. If a filing were analysed twice with different results, the
  first one would stay.
- Ingestion and Reporting apply the same rule, so the status and the report agree on the outcome.
