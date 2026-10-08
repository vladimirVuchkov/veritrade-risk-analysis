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
- Raw record: [`03-orchestrator-waves-1-3.md`, subagent transcript `agent-ab89de28fb5b1014c`](../ai-conversations/03-orchestrator-waves-1-3.md#subagent-transcript-agent-ab89de28fb5b1014c)
- Asked for: the Wave 1 frontend (D1 to D5) as plain HTML, CSS and ES modules with no build step;
  pure, testable modules for validation, the API client, polling, highlighting and formatting; DOM
  rendering only through text nodes; a dependency-free mock server; full unit tests and end-to-end
  tests with `node --test`; and this handoff note.
- Received: the UI in `frontend/` (form, polling flow, report view, recent filings, responsive CSS),
  the Node mock server in `frontend/mock/`, 188 passing tests in `frontend/test/` (including the
  end-to-end and XSS tests), `frontend/README.md`, and this note.
- Fixed by hand: nothing; retrying 502/503/504 during polling was requested after the Compose run and done by the agent

## Wave 3

### Done
- W3-05 (late poll response overwrote the progress line of the filing on screen):
  - `js/polling.js`: `pollUntil` checks cancellation again after every request, before `onValue` and
    `isDone`, so a response that arrives after cancellation is dropped. An error thrown by a request of
    a cancelled flow is dropped too (outcome `CANCELLED`). `runAnalysisFlow` takes an `AbortSignal`
    (`signal`), passes it to every request and to the sleep, and treats an aborted signal as cancelled.
    `realSleep(ms, signal)` wakes up at once on abort.
  - `js/api.js`: `getFiling` and `getReport` take `{ signal }` and pass it to `fetch`. An aborted
    request becomes `ApiError` with kind `aborted`, not a network error, so it is never retried.
  - New `js/flow-session.js` (no DOM): `createLatestOnly()` hands out a token per flow (a generation
    number plus an `AbortController`); starting a new flow aborts the previous one.
    `createFlowSession` checks the token before every view update (status line, outcome, error, report,
    list refresh). `js/app.js` only wires the session to the DOM.
- Switching edge cases:
  - opening a recent filing while another flow runs: the old flow is aborted and nothing it receives
    later reaches the screen (status, outcome, error or report);
  - submitting twice quickly: a second submit while the first POST is in flight is ignored (the button
    is also disabled). Starting a submit stops the flow on screen and shows "Submitting the filing…".
    The POST itself is never aborted, because it has a side effect;
  - opening another filing while a submit is in flight: the accepted filing is not followed, the form
    still says it was accepted, and the list is refreshed so it can be opened;
  - a report for a filing no longer shown is never rendered;
  - concurrent refreshes of the recent filings list: only the latest response is rendered.
- CSP readiness for `Content-Security-Policy: default-src 'self'`: `index.html` has one external module
  script and one local stylesheet, no inline script, no `<style>`, no `style=` attributes and no event
  handler attributes; `styles.css` loads nothing; the JS has no `eval`, no `new Function`, no string
  timers and sets no style attribute. Nothing had to change. `test/csp.test.js` enforces this, and
  also checks that its own patterns catch each forbidden construct. The mock now sends the same header
  for the UI files, so a manual check against the mock runs under the policy.
- Tests: 218 in `frontend/test/` (188 before Wave 3).
  - `test/cancellation.test.js` (11): late status, late terminal status, late report, signal passed to
    every request, abort stops polling, aborted request means cancelled, real `fetch` aborted through
    the API client, `aborted` error kind, `realSleep` abort. All 11 fail on the code at `main`.
  - `test/flow-session.test.js` (10): switching filings with late status, late terminal status, late
    report and late error; double submit; submit while a flow runs; opening a filing while a submit is
    in flight; submit failure. With the polling fix reverted and the token check removed (the old
    `followFiling` behaviour), 6 of the 10 fail; the other 4 cover submit rules that did not exist.
  - `test/e2e.test.js`: two overlapping flows against the mock. The first filing's status request is
    held, the second filing is submitted, and the held response is released after the first filing has
    become FAILED. The UI log after the switch shows only the second filing (no failure text, no
    "Stopped waiting", one report for the second filing). It fails on the old polling code and also
    with the token check removed.
  - `test/csp.test.js` (8): the CSP checks above.

### Known issues and limitations
- The headless browser check under the CSP header was not run in this session; the policy is enforced
  by the static test and by the mock header.
- `js/app.js` still has no automated test, but its logic moved to `js/flow-session.js`, which is tested.

### How to verify
- `node --test frontend/test/` prints `tests 218`, `pass 218`, `fail 0`.
- `node frontend/mock/server.js`, submit the sample, then open another filing from the list while the
  first is analysing: the progress line only ever shows the filing that was opened last.

### AI record
- Raw record: [`03-orchestrator-waves-1-3.md`, subagent transcript `agent-a4ea4300e7506123a`](../ai-conversations/03-orchestrator-waves-1-3.md#subagent-transcript-agent-a4ea4300e7506123a)
- Asked for: the W3-05 fix with airtight cancellation (generation token or AbortController, in-flight
  requests aborted), CSP readiness with a static test, the switching edge cases, unit tests that fail
  on the old code, and an e2e test with two overlapping flows against the mock.
- Received: the changes and tests above, and this section.
- Fixed by hand: nothing
