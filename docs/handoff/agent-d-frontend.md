# Handoff - Agent D (Frontend)

## Done
- D1 `frontend/index.html`: form (company, title, text or `.txt` upload), "Load sample" button.
  The sample filing is in `frontend/js/sample.js`.
- D2 `frontend/js/polling.js` + `js/app.js`: submit, then poll the status every 2 s (at most 30
  attempts), then poll the report until the 404 becomes a 200 (at most 15 attempts). A timeout shows
  a clear message, and FAILED shows the `failureReason`. Network errors and gateway responses 502, 503
  and 504 (nginx while an upstream restarts) count as transient: polling continues, and only when more
  than 3 come in a row does it stop with a clear "Service unavailable" message. Other errors such as
  500 stop at once. Every interval and limit is in `frontend/js/config.js`
  (`maxConsecutiveTransientErrors`, `transientHttpStatuses`).
- D3 `frontend/js/render.js`: overall risk level, a category by severity table, a findings table, and
  excerpts with the match highlighted (`js/highlight.js`). Untrusted text goes only through text nodes
  and `createElement`, never `innerHTML`.
- D4 Recent filings (`GET /api/filings?limit=10`). Choosing a filing in the list opens it again.
- D5 Responsive layout, checked in a browser against the mock at 390 px (no horizontal page scroll,
  tables stack into cards) and at a wide size. Form controls have labels, errors use `aria-invalid` and
  live regions, and outcome messages use `role="status"` or `role="alert"`.
- Client-side validation: required fields, name lengths (200 and 300 characters), text of at most
  2,097,152 UTF-8 bytes (`TextEncoder`), and `.txt` uploads only. Server ProblemDetail errors
  (`title: detail`) are shown to the user. A non-JSON error body (for example an nginx 413 or 502 page)
  is shown as a generic message with the HTTP status.
- `frontend/mock/server.js`: a dependency-free Node mock of the OpenAPI contract with simulated
  delays, a report that returns 404 first, FAILED filings (`[fail]` marker), a 500 (`[server-error]`
  marker), 503 problem+json for the first N report requests (`--unavailable-reports N`), 400 ProblemDetails, a 404 for unknown ids, and an HTML 413 for bodies over 3 MB. It also
  serves the UI.
- Tests: 188 tests in `frontend/test/` with `node:test` and no dependencies: unit tests for
  highlighting, validation, the API client, polling, formatting, rendering and XSS (against a fake
  document whose `innerHTML` throws), the mock, and static checks of `index.html` and the sources; plus
  an end-to-end test (`e2e.test.js`) that starts the mock on a random port and drives the full HTTP flow.

## Known issues and limitations
- The excerpt does not say where it starts in the filing, so the highlight uses `position` when the
  excerpt starts at the beginning of the filing, then `min(position, 120)` (the 120-character context
  seen in `docs/contracts/examples/analysis-completed.json`), then the first exact match, then a
  case-insensitive match. If none matches, the excerpt is shown without a highlight.
  `excerptContextChars` in `js/config.js` must match the context width that Analysis (Agent B) uses.
- `position` is treated as a UTF-16 code unit offset, the same as a Java `String` index.
- `js/app.js` (DOM wiring only) has no automated test. It was checked by hand in a browser against
  the mock: blank submit, the sample flow to COMPLETED, the FAILED flow, and the 390 px layout.
- Node 22 does not expand a directory passed to `node --test`, so `frontend/test/index.js` loads every
  `*.test.js` file when it is started as the directory entry. It does nothing when it runs on its own.
- Tests were run with Node v22.23.2.
- Requests to Agent E (nginx, `infra/nginx/default.conf`):
  - serve `frontend/` as the web root with `index.html` as the index;
  - make sure `.js` is served as `text/javascript` or `application/javascript` (needed for
    `<script type="module">`, and the default `mime.types` already does this);
  - optionally deny `/mock/`, `/test/`, `package.json` and `README.md`, or copy only `index.html`,
    `styles.css` and `js/` into the image. These files hold no secrets, but production does not need them;
  - `client_max_body_size 3m`, as in the plan, so a 2 MB filing reaches Ingestion.

## How to verify
- `node --test frontend/test/` prints `tests 188`, `pass 188`, `fail 0`.
- `node frontend/mock/server.js`, then open `http://127.0.0.1:8090/`, choose "Load sample" and
  "Submit for analysis". The status moves through Submitted and Analyzing, and the report appears with
  highlighted excerpts. Add `[fail]` to the title to see the failure reason.

## AI record
- Raw record: exported by the orchestrator from its session (subagent transcript)
- Asked for: the Wave 1 frontend (D1 to D5) as plain HTML, CSS and ES modules with no build step;
  pure, testable modules for validation, the API client, polling, highlighting and formatting; DOM
  rendering only through text nodes; a dependency-free mock server; full unit tests and end-to-end
  tests with `node --test`; and this handoff note.
- Received: the UI in `frontend/` (form, polling flow, report view, recent filings, responsive CSS),
  the Node mock server in `frontend/mock/`, 188 passing tests in `frontend/test/` (including the
  end-to-end and XSS tests), `frontend/README.md`, and this note.
- Fixed by hand: nothing; retrying 502/503/504 during polling was requested after the Compose run and done by the agent
