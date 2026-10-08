# 0003 - A shared contracts module

## Context
Three services exchange four event types. Copies of the event classes in every service drift apart,
and here the services were written by parallel agents, which makes drift even more likely.

## Decision
A small plain-Java module, `common-contracts`, holds only contract types:
- the `EventEnvelope` and the payload records;
- the shared enums (`EventType`, `RiskCategory`, `Severity`, `RiskLevel`);
- the topology names (`MessagingTopology`);
- the deterministic event-id helper (`EventIds`);
- the correlation-id names (`CorrelationIds`).

It has no Spring dependency and no business logic. The language-neutral reference stays in
[`docs/contracts/`](../contracts/): JSON Schemas, examples, the OpenAPI file and the topology. A test
inside the module validates every example against its schema and round-trips it through the records.
REST DTOs, entities and logic stay inside each service.

## Consequences
- There is one definition, checked at compile time. A contract change breaks the build of every
  service that is affected.
- The services share one Maven build and are released together. That is acceptable in one repository.
- The module must stay small. The rule "contract types only" is enforced by review.
- The records carry no Jackson annotations, and consumers ignore unknown fields, so an added optional
  field does not break an older consumer. Adding an enum value is still a breaking change (see the
  README limitations).
