# Frontend

Plain HTML, CSS and ES modules. nginx serves the files as they are, with no build step and no Node
in the production stack. The UI calls the same-origin paths `/api/filings` and `/api/reports`.

## Layout

| Path | Content |
|---|---|
| `index.html`, `styles.css` | page and responsive layout (390 px and wide screens) |
| `js/config.js` | polling interval, attempt limits, size limits, enums |
| `js/validation.js` | required fields, lengths, 2 MB UTF-8 byte limit (`TextEncoder`) |
| `js/api.js` | Fetch client; RFC 9457 errors become `ApiError` |
| `js/polling.js` | status polling, then report polling until the 404 turns into a 200 |
| `js/highlight.js` | splits an excerpt into plain and highlighted segments |
| `js/format.js` | labels, CSS classes, counts and user messages |
| `js/render.js` | DOM builders; untrusted text goes only through text nodes, never `innerHTML` |
| `js/app.js` | DOM wiring only |
| `js/sample.js` | the sample filing used by "Load sample" |
| `mock/` | dependency-free mock server of the REST contract |
| `test/` | unit and end-to-end tests (`node:test`) |

`package.json` only marks the `.js` files as ES modules for Node. It has no dependencies.

## Run the mock server

Node 22 or newer is needed. From the repository root:

```
node frontend/mock/server.js            # http://127.0.0.1:8090/
node frontend/mock/server.js --port 9000
```

The mock serves the UI and implements the OpenAPI contract in memory:

- a filing stays `SUBMITTED` for 1.5 s, then `ANALYZING` for 3 s, then `COMPLETED`;
- the report returns 404 for another 2.5 s, so the UI's report polling is exercised;
- `[fail]` in the title or text ends the filing in `FAILED` with a failure reason;
- `[server-error]` in the title makes the submission return a 500 ProblemDetail;
- `--unavailable-reports N` (or the `unavailableReportRequests` option) answers the first N report
  requests with a 503 problem+json, the way nginx does while Reporting restarts;
- invalid input (blank fields, text over 2 MB, invalid JSON, a bad `limit`, a bad id) returns a 400
  ProblemDetail, and a body over 3 MB returns an HTML 413 page like nginx does.

The timings and markers are in `mock/mock-config.js`.

## Run the tests

```
node --test frontend/test/
```

This runs the unit tests and an end-to-end test that starts the mock server on a random free port
and drives submit, status polling and the report over HTTP, including the FAILED, 400, 404, 413,
500, 503 (Reporting restarting), timeout, network-error and XSS paths. DOM builders are checked against a minimal fake document
whose `innerHTML` throws.
