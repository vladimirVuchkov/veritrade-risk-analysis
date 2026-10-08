# 0004 - Transactional outbox with publisher confirms (Ingestion)

## Context
Ingestion must store a filing and publish `filing.submitted`. A database commit and a broker publish
cannot be made atomic. If the service publishes first, it can announce a filing that was never
stored. If it stores first, the event is lost when the service stops before it publishes.

## Decision
- `FilingService.submit` writes the filing and an `outbox` row in one transaction.
  `OutboxService.enqueue` refuses to run outside a transaction (`Propagation.MANDATORY`).
- `OutboxPublisher` runs on a fixed delay (500 ms by default) and sends unpublished rows in
  `created_at, id` order.
- A row is marked published only after a positive publisher confirm with no return
  (`publisher-confirm-type: correlated`, `publisher-returns: true`, `mandatory: true`).
- On a nack, a return, a confirm timeout (5 s) or a broker error, the run stops. The same row is sent
  again on the next run.
- Only Ingestion has an outbox.

## Consequences
- A stored filing is always announced, even after a crash or while the broker is down.
- Delivery is at-least-once. A lost confirm sends the row again with the same `messageId`
  (= `eventId`), and consumers drop the repeat.
- Order is kept, because the publisher stops at the first failure.
- Only one instance may run the publisher (`ingestion.outbox.enabled=false` turns it off). Running
  several would need row locking (`SELECT ... FOR UPDATE SKIP LOCKED`) or leader election.
- Analysis publishes directly, with confirms but without an outbox. It has no database. A failed
  publish is retried by the listener retry, and deterministic event ids make the repeats harmless.
- Published rows are never deleted. Production would add a cleanup job.
