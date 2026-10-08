# 0009 - Deterministic event ids

## Context
Consumers drop repeated events by `eventId`. Analysis has no database: when a `filing.submitted` is
redelivered, Analysis analyses the filing again and publishes its events again. With random ids
these repeats would look like new events.

## Decision
Every event id is a name-based UUID of the filing id and the event type, computed by
`EventIds.forFiling(filingId, eventType)` in `common-contracts`. A filing produces at most one event
of each type. The event id is also the AMQP `messageId` and, in Ingestion, the outbox row id.

## Consequences
- A redelivered, retried or recomputed event keeps its id. Reporting drops it through its
  `processed_events` table; Ingestion sees the same target status and ignores it.
- Analysis needs no table to be idempotent.
- A deliberate re-analysis of the same filing (for example with new rules) would reuse the ids and be
  dropped. That would need a new event type or an analysis-run id in the name.
