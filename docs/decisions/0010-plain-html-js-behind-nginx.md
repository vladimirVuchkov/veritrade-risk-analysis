# 0010 - Plain HTML and JavaScript behind nginx

## Context
The UI is small: a form, a status poller, a report view and a list of recent filings. It calls two
backend APIs.

## Decision
- The frontend is plain HTML, CSS and ES modules using the Fetch API, with no framework and no build
  step.
- nginx serves the files and is also the reverse proxy for `/api/filings` (Ingestion) and
  `/api/reports` (Reporting), so the browser talks to one origin.
- Untrusted text is rendered only through text nodes, never through `innerHTML`.
- A dependency-free Node mock server ([`frontend/mock/`](../../frontend/mock/)) implements the REST
  contract for development and for the `node:test` tests.

## Consequences
- No CORS configuration and no Node in the production stack.
- Only nginx (port 8080) and the RabbitMQ management UI are published to the host.
- No component library and no type checking. The code is split into small, pure, tested modules
  instead.
- nginx must accept request bodies of up to 3 MB (`client_max_body_size 3m`). Its 1 MB default would
  reject a 2 MB filing with 413.
