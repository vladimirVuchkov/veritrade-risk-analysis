# 0005 - One queue per consuming service

## Context
Ingestion and Reporting each consume several event types. One queue per event type would multiply
the queues and the dead-letter queues, and RabbitMQ keeps order only within one queue.

## Decision
Each consuming service has exactly one work queue on `veritrade.events`:

| Queue | Bindings |
|---|---|
| `analysis.filing-submitted` | `filing.submitted` |
| `ingestion.analysis-events` | `analysis.*` |
| `reporting.analysis-results` | `analysis.completed`, `analysis.failed` |

The listener reads the raw message and dispatches on the `eventType` field, never on a Java type
header. Each work queue has one dead-letter queue, `<queue>.dlq`, bound to the direct exchange
`veritrade.dlx`.

## Consequences
- The events of one filing reach a consumer in publish order unless a message is redelivered.
  Consumers still tolerate any order.
- There are three work queues and three dead-letter queues in total.
- A slow event type can delay another type in the same queue. That is acceptable at this scale.
