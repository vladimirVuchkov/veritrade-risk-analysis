# 0011 - Park outbox rows that can never be published

## Context
The outbox publisher ([ADR 0004](0004-outbox-with-publisher-confirms.md)) stops at the first failure,
so that later rows never overtake an earlier one. The Wave 3 review found that one row the AMQP client
can never send (for example a `correlationId` over the 255-byte AMQP short-string limit) would then
block every later filing for good. Simply skipping failed rows is not safe either: during a broker
outage every row fails, and skipping would reorder or drop them.

## Decision
- A failure counts against a row only when the message is refused before anything reaches the broker:
  an `IllegalArgumentException` anywhere in the cause chain (the AMQP client's encoding checks) or a
  `MessageConversionException` (`PublishFailures`). Such a message fails the same way whatever the
  state of the broker.
- Connection, I/O, timeout, authentication and channel-limit failures never count, even with an
  `IllegalArgumentException` among their causes. Nor do any other `AmqpException`, a nack, a return as
  unroutable or a missing confirm.
- A counted failure stops the run like before. After `ingestion.outbox.max-attempts` (default 5) the
  row is parked: `parked_at` is set, an ERROR line names the event, the row is never sent again and the
  run goes on with the next rows.
- Migration `V2__outbox_attempts.sql` adds `attempts` (default 0), `last_error` and `parked_at`, all
  defaulted or nullable, so V1 rows need no data change.
- After any run that stops early, the publisher pauses with an exponential back-off
  (`ingestion.outbox.retry-backoff` 1 s, `retry-backoff-multiplier` 2, `max-retry-backoff` 10 s). The
  next successful run resets it.

## Consequences
- One poison row no longer blocks the outbox, and a broker outage never parks a row.
- Each row is the only event of its filing, so parking one reorders no other filing's events.
- A parked row leaves its filing `SUBMITTED`. There is no replay tool; parked rows are found with
  `select id, attempts, last_error, parked_at from outbox where parked_at is not null`.
- A still unknown kind of poison message that is not refused with an `IllegalArgumentException` is
  treated as transient and blocks the outbox as before. This is the safe side: an unproven row fault
  must not reorder or drop rows.
- The back-off delays the first publish after an outage by up to 10 s, but a long outage no longer
  reloads every pending payload (up to 2 MB each) every 500 ms.
