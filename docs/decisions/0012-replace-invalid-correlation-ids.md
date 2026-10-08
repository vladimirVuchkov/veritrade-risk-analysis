# 0012 - Replace an invalid correlation id instead of rejecting the request

## Context
Ingestion takes the client's `X-Correlation-Id` and carries it in the logs, the event envelope, the
`outbox.correlation_id` column and the AMQP `correlationId` property. The Wave 3 review showed that a
non-ASCII header could exceed the 255-byte AMQP short string and block the outbox. The OpenAPI
declares the header as an optional string with `maxLength: 128` and no pattern, and the `GET`
operations declare no 400 response.

## Decision
- `CorrelationIdFilter` keeps a client id only when it is a strict ASCII token: 1 to 128 characters
  from `[A-Za-z0-9._:-]`, after surrounding whitespace is stripped.
- Any other value (empty, blank, too long, non-ASCII, control characters, inner spaces, other
  punctuation) is replaced with a generated UUID, as a missing id already was. The request is never
  rejected for it. The response returns the id that is actually used.

## Consequences
- A kept id is at most 128 bytes, so it always fits the AMQP short string, the database column and a
  log line, and it contains no control characters.
- No new 400 for a value the OpenAPI allows, and no contract change.
- A client that sends an unusual id gets a different one back and must use the returned header to
  find its request in the logs.
- The outbox still parks a row that cannot be encoded ([ADR 0011](0011-park-poison-outbox-rows.md)),
  as a second line of defence.
