# Messaging topology

This is the exact RabbitMQ topology. There is no broker definitions file: every service declares
the exchanges, queues and bindings it uses in its own `RabbitConfig`, with exactly the names and
arguments below. RabbitMQ declarations are idempotent when the arguments match, so services can start
in any order. A mismatch in arguments makes the declaration fail (`PRECONDITION_FAILED`), which is
why the arguments are fixed here and the names live in
`common-contracts` (`com.veritrade.contracts.messaging.MessagingTopology`).

## Exchanges

| Name | Type | Durable | Auto-delete | Purpose |
|---|---|---|---|---|
| `veritrade.events` | topic | yes | no | All domain events |
| `veritrade.dlx` | direct | yes | no | Dead letters from the work queues |

## Routing keys

| Routing key | Event type (`eventType`) | Producer |
|---|---|---|
| `filing.submitted` | `FILING_SUBMITTED` | Ingestion (through the outbox) |
| `analysis.started` | `ANALYSIS_STARTED` | Analysis |
| `analysis.completed` | `ANALYSIS_COMPLETED` | Analysis |
| `analysis.failed` | `ANALYSIS_FAILED` | Analysis |

## Work queues

One queue per consumer. A single queue per consumer keeps the order of the events of one filing as
published, and keeps the number of dead-letter queues small.

| Queue | Owner (declares and consumes) | Bindings on `veritrade.events` |
|---|---|---|
| `analysis.filing-submitted` | Analysis | `filing.submitted` |
| `ingestion.analysis-events` | Ingestion | `analysis.*` |
| `reporting.analysis-results` | Reporting | `analysis.completed`, `analysis.failed` |

Every work queue is declared with:

| Property / argument | Value |
|---|---|
| durable | `true` |
| exclusive | `false` |
| auto-delete | `false` |
| queue type | classic (default; no `x-queue-type` argument) |
| `x-dead-letter-exchange` | `veritrade.dlx` |
| `x-dead-letter-routing-key` | the work queue name, e.g. `analysis.filing-submitted` |

No other arguments. In particular there is no `x-message-ttl` and no `x-max-length`: a message
must wait for as long as its consumer is down, and must never expire or be dropped silently.

## Dead-letter queues

| Queue | Bound to `veritrade.dlx` with key |
|---|---|
| `analysis.filing-submitted.dlq` | `analysis.filing-submitted` |
| `ingestion.analysis-events.dlq` | `ingestion.analysis-events` |
| `reporting.analysis-results.dlq` | `reporting.analysis-results` |

Dead-letter queues are durable, with no arguments. The owner of the work queue declares its
dead-letter queue and binding, and also declares `veritrade.dlx`. Nothing consumes the dead-letter
queues; they are inspected through the management UI (port 15672).

## Producer rules

- Exchange: `veritrade.events`; routing key from the table above (`EventType.routingKey()`).
- Body: the JSON `EventEnvelope` (see the `*.schema.json` files), UTF-8.
- AMQP properties:
  - `messageId` = `eventId`
  - `contentType` = `application/json`
  - `correlationId` = the envelope `correlationId`
  - `deliveryMode` = persistent
- `mandatory` = `true`, so an unroutable message is returned to the producer instead of being dropped.
- Publisher confirms are on (`spring.rabbitmq.publisher-confirm-type: correlated`,
  `spring.rabbitmq.publisher-returns: true`). Ingestion marks an outbox row as published only after
  a positive confirm without a return.
- Event ids are deterministic: `EventIds.forFiling(filingId, eventType)`. A recomputed or
  republished event keeps the same id.
- Spring AMQP JSON converter: `JacksonJsonMessageConverter` (Jackson 3), built with the
  application `JsonMapper` from Spring Boot.

## Consumer rules

- Acknowledge only after successful processing (Spring AMQP `AUTO` acknowledge mode: the message is
  acknowledged when the listener returns normally).
- Prefetch: `10`.
- Tolerant reader: unknown JSON fields are ignored (`FAIL_ON_UNKNOWN_PROPERTIES` disabled, which is
  the Spring Boot default for its `JsonMapper`). Consumers dispatch on `eventType` or on the routing
  key, never on a Java type header (`__TypeId__`).
- Deduplication by `eventId`. Redelivery is normal (at-least-once delivery).
- Retries: the Spring AMQP listener retry (`spring.rabbitmq.listener.simple.retry.*`): 3 attempts,
  initial interval 1 s, multiplier 2, max interval 5 s. Then the recoverer runs:
  - Ingestion and Reporting: `RejectAndDontRequeueRecoverer` -> the message goes to the queue's `.dlq`.
  - Analysis: see the two failure paths below.
- A message that cannot be read or validated (invalid JSON, missing required fields, unknown
  `eventType`) is not retried; it goes straight to the `.dlq`.

### Analysis failure paths

1. Unreadable or invalid `filing.submitted` message -> straight to `analysis.filing-submitted.dlq`,
   no retries. No `analysis.failed` is published because the filing id cannot be trusted; the filing
   stays `SUBMITTED` (known limitation; in production a periodic sweep would flag stuck filings).
2. Processing error that persists after 3 attempts -> Analysis publishes `analysis.failed` with the
   reason, then acknowledges the original message.

## Ordering, late and contradictory events

- RabbitMQ keeps the order within one queue, not across queues. Events of one filing reach a given
  consumer in publish order unless a message is redelivered, so consumers must still tolerate any order.
- Filing status in Ingestion: `SUBMITTED -> ANALYZING -> COMPLETED | FAILED`.
  `SUBMITTED -> COMPLETED | FAILED` directly is allowed (e.g. `analysis.started` is late or lost).
- `COMPLETED` and `FAILED` are final. The first terminal event wins.
- A late or contradictory event (for example `analysis.started` after `analysis.completed`, or
  `analysis.failed` after `analysis.completed`) is acknowledged and ignored, with a `WARN` log line
  that includes the `eventId`, the `filingId` and both states. It never throws, because a thrown
  exception would retry and then dead-letter a perfectly valid message.
- This rule applies to Ingestion (filing status) and Reporting (report).

## Event versioning

- `eventVersion` starts at 1. Additive changes (new optional fields) keep the version; consumers
  ignore unknown fields.
- A breaking change gets a new version; consumers support the old and the new version during the
  rollout.
