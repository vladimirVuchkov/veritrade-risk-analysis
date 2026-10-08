import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { createApiClient, ERROR_KINDS } from '../js/api.js';
import { CONFIG } from '../js/config.js';
import { problemMessage } from '../js/format.js';
import { OUTCOMES, PHASES, realSleep, runAnalysisFlow } from '../js/polling.js';
import {
  FAST_SETTINGS,
  filing,
  httpFailure,
  jsonResponse,
  networkFailure,
  problemResponse,
  recordingSleep,
  scriptedApi,
  scriptedFetch,
  textResponse,
} from './support/fakes.js';

const ID = '3f2b8c1e-6a4d-4e2f-9b7a-1c5d8e9f0a12';
const REPORT = { filingId: ID, status: 'COMPLETED', findings: [], summary: null };

function run(api, overrides = {}) {
  const { sleep, delays } = recordingSleep();
  const statuses = [];
  const phases = [];
  const promise = runAnalysisFlow({
    api,
    filingId: ID,
    sleep,
    onStatus: (value) => statuses.push(value.status),
    onPhase: (phase) => phases.push(phase),
    settings: FAST_SETTINGS,
    ...overrides,
  });
  return { promise, delays, statuses, phases };
}

async function rejection(promise) {
  try {
    await promise;
  } catch (error) {
    return error;
  }
  throw new Error('Expected a rejection');
}

describe('runAnalysisFlow', () => {
  it('follows SUBMITTED -> ANALYZING -> COMPLETED, then waits for the report 404 -> 200', async () => {
    const api = scriptedApi({
      filings: [filing('SUBMITTED'), filing('ANALYZING'), filing('COMPLETED')],
      reports: [null, null, REPORT],
    });
    const { promise, delays, statuses, phases } = run(api);
    const result = await promise;
    assert.equal(result.outcome, OUTCOMES.completed);
    assert.deepEqual(result.report, REPORT);
    assert.equal(result.filing.status, 'COMPLETED');
    assert.deepEqual(statuses, ['SUBMITTED', 'ANALYZING', 'COMPLETED']);
    assert.deepEqual(phases, [PHASES.status, PHASES.report]);
    assert.deepEqual(api.calls, { getFiling: 3, getReport: 3 });
    assert.deepEqual(delays, Array(4).fill(FAST_SETTINGS.intervalMs));
  });

  it('does not wait before the first request when the filing is already complete', async () => {
    const api = scriptedApi({ filings: [filing('COMPLETED')], reports: [REPORT] });
    const { promise, delays } = run(api);
    assert.equal((await promise).outcome, OUTCOMES.completed);
    assert.deepEqual(delays, []);
  });

  it('stops at FAILED with the failure reason and never asks for the report', async () => {
    const api = scriptedApi({
      filings: [filing('SUBMITTED'), filing('FAILED', { failureReason: 'rule engine error' })],
    });
    const { promise, phases } = run(api);
    const result = await promise;
    assert.equal(result.outcome, OUTCOMES.failed);
    assert.equal(result.reason, 'rule engine error');
    assert.equal(api.calls.getReport, 0);
    assert.deepEqual(phases, [PHASES.status]);
  });

  it('reports FAILED without a reason as a null reason', async () => {
    const api = scriptedApi({ filings: [filing('FAILED', { failureReason: undefined })] });
    assert.equal((await run(api).promise).reason, null);
  });

  it('times out when the status never becomes terminal', async () => {
    const api = scriptedApi({ filings: [filing('ANALYZING')] });
    const { promise, delays } = run(api);
    const result = await promise;
    assert.equal(result.outcome, OUTCOMES.timeout);
    assert.equal(result.phase, PHASES.status);
    assert.equal(result.filing.status, 'ANALYZING');
    assert.equal(api.calls.getFiling, FAST_SETTINGS.statusMaxAttempts);
    assert.equal(delays.length, FAST_SETTINGS.statusMaxAttempts - 1);
  });

  it('keeps polling through unknown status values', async () => {
    const api = scriptedApi({ filings: [filing('PAUSED'), filing('COMPLETED')], reports: [REPORT] });
    assert.equal((await run(api).promise).outcome, OUTCOMES.completed);
  });

  it('times out when the report stays 404', async () => {
    const api = scriptedApi({ filings: [filing('COMPLETED')], reports: [null] });
    const result = await run(api).promise;
    assert.equal(result.outcome, OUTCOMES.timeout);
    assert.equal(result.phase, PHASES.report);
    assert.equal(result.filing.status, 'COMPLETED');
    assert.equal(api.calls.getReport, FAST_SETTINGS.reportMaxAttempts);
  });

  it('retries after a transient network error', async () => {
    const api = scriptedApi({
      filings: [networkFailure(), filing('ANALYZING'), networkFailure(), filing('COMPLETED')],
      reports: [networkFailure(), REPORT],
    });
    assert.equal((await run(api).promise).outcome, OUTCOMES.completed);
  });

  it('gives up when consecutive network errors exceed the configured limit', async () => {
    const api = scriptedApi({ filings: [networkFailure()] });
    const error = await rejection(run(api).promise);
    assert.equal(error.kind, ERROR_KINDS.network);
    assert.match(problemMessage(error.problem), /^Network error/);
    assert.equal(api.calls.getFiling, FAST_SETTINGS.maxConsecutiveTransientErrors + 1);
  });

  it('times out instead of failing when network errors are not consecutive', async () => {
    const api = scriptedApi({
      filings: [networkFailure(), networkFailure(), filing('ANALYZING'), networkFailure(), networkFailure(), filing('ANALYZING')],
    });
    assert.equal((await run(api).promise).outcome, OUTCOMES.timeout);
  });

  it('stops immediately on a 500 ProblemDetail from the status endpoint', async () => {
    const api = scriptedApi({ filings: [httpFailure(500, { title: 'Internal Server Error', status: 500 })] });
    const error = await rejection(run(api).promise);
    assert.equal(error.status, 500);
    assert.equal(api.calls.getFiling, 1);
  });

  it('stops immediately on a 500 ProblemDetail from the report endpoint', async () => {
    const api = scriptedApi({
      filings: [filing('COMPLETED')],
      reports: [null, httpFailure(500, { title: 'Internal Server Error', status: 500 })],
    });
    assert.equal((await rejection(run(api).promise)).status, 500);
    assert.equal(api.calls.getReport, 2);
  });

  it('can be cancelled between attempts', async () => {
    const api = scriptedApi({ filings: [filing('SUBMITTED')] });
    let cancelled = false;
    const result = await run(api, {
      onStatus: () => {
        cancelled = true;
      },
      isCancelled: () => cancelled,
    }).promise;
    assert.equal(result.outcome, OUTCOMES.cancelled);
    assert.equal(api.calls.getFiling, 1);
  });

  it('can be cancelled while waiting for the report', async () => {
    let cancelled = false;
    const api = scriptedApi({ filings: [filing('COMPLETED')], reports: [null] });
    const result = await run(api, {
      onPhase: (phase) => {
        cancelled = phase === PHASES.report;
      },
      isCancelled: () => cancelled,
    }).promise;
    assert.equal(result.outcome, OUTCOMES.cancelled);
    assert.equal(result.phase, PHASES.report);
    assert.equal(api.calls.getReport, 0);
  });

  it('uses the configured 2 s interval by default', async () => {
    const api = scriptedApi({ filings: [filing('SUBMITTED'), filing('COMPLETED')], reports: [REPORT] });
    const { sleep, delays } = recordingSleep();
    await runAnalysisFlow({ api, filingId: ID, sleep });
    assert.deepEqual(delays, [CONFIG.polling.intervalMs]);
    assert.equal(CONFIG.polling.intervalMs, 2000);
  });
});

describe('runAnalysisFlow with the real API client', () => {
  function flowWith(steps) {
    const { fetchFn, calls } = scriptedFetch(steps);
    const api = createApiClient({ fetchFn });
    return { promise: run(api).promise, calls };
  }

  it('turns report 404 responses into waiting and finishes on 200', async () => {
    const notFound = () => problemResponse(404, { title: 'Not Found', status: 404 });
    const { promise, calls } = flowWith([
      jsonResponse(200, filing('SUBMITTED')),
      jsonResponse(200, filing('ANALYZING')),
      jsonResponse(200, filing('COMPLETED')),
      notFound(),
      notFound(),
      jsonResponse(200, REPORT),
    ]);
    const result = await promise;
    assert.equal(result.outcome, OUTCOMES.completed);
    assert.deepEqual(calls.map((call) => call.url.split('/')[2]), [
      'filings', 'filings', 'filings', 'reports', 'reports', 'reports',
    ]);
  });

  it('surfaces a 500 ProblemDetail detail', async () => {
    const { promise } = flowWith([problemResponse(500, { title: 'Internal Server Error', detail: 'db down' })]);
    const error = await rejection(promise);
    assert.equal(error.problem.detail, 'db down');
  });

  it('surfaces a non-JSON error body with a readable message', async () => {
    const { promise } = flowWith([textResponse(500, '<html>Internal Server Error</html>')]);
    const error = await rejection(promise);
    assert.equal(error.status, 500);
    assert.match(error.problem.detail, /HTTP 500/);
  });

  it('retries after fetch itself rejects', async () => {
    const { promise } = flowWith([
      new TypeError('fetch failed'),
      jsonResponse(200, filing('COMPLETED')),
      jsonResponse(200, REPORT),
    ]);
    assert.equal((await promise).outcome, OUTCOMES.completed);
  });
});

const unavailable = (status) => httpFailure(status, {
  type: 'about:blank', title: 'Service Unavailable', status, detail: 'upstream restarting',
});

describe('transient gateway errors (502, 503, 504)', () => {
  it('uses 502, 503 and 504 as the configured transient statuses', () => {
    assert.deepEqual([...CONFIG.polling.transientHttpStatuses], [502, 503, 504]);
  });

  for (const status of [502, 503, 504]) {
    it(`keeps polling the report through ${status}, ${status}, then 200`, async () => {
      const api = scriptedApi({
        filings: [filing('COMPLETED')],
        reports: [unavailable(status), unavailable(status), REPORT],
      });
      const result = await run(api).promise;
      assert.equal(result.outcome, OUTCOMES.completed);
      assert.deepEqual(result.report, REPORT);
      assert.equal(api.calls.getReport, 3);
    });

    it(`keeps polling the status through ${status}`, async () => {
      const api = scriptedApi({
        filings: [unavailable(status), filing('ANALYZING'), unavailable(status), filing('COMPLETED')],
        reports: [REPORT],
      });
      assert.equal((await run(api).promise).outcome, OUTCOMES.completed);
    });

    it(`gives up with a clear message when ${status} repeats past the limit`, async () => {
      const api = scriptedApi({ filings: [filing('COMPLETED')], reports: [unavailable(status)] });
      const error = await rejection(run(api).promise);
      assert.equal(error.kind, ERROR_KINDS.http);
      assert.equal(error.status, status);
      assert.equal(api.calls.getReport, FAST_SETTINGS.maxConsecutiveTransientErrors + 1);
      assert.equal(
        problemMessage(error.problem),
        `Service unavailable: The service is temporarily unavailable (HTTP ${status}) and did not recover `
          + `after ${FAST_SETTINGS.maxConsecutiveTransientErrors} attempts in a row. Please try again later.`,
      );
      assert.equal(error.cause.problem.detail, 'upstream restarting');
    });
  }

  it('counts network errors and gateway errors together as consecutive transient errors', async () => {
    const api = scriptedApi({
      filings: [filing('COMPLETED')],
      reports: [networkFailure(), unavailable(503), networkFailure(), unavailable(504)],
    });
    const error = await rejection(run(api).promise);
    assert.equal(error.status, 504);
    assert.equal(api.calls.getReport, 4);
  });

  it('resets the count after a successful response', async () => {
    const api = scriptedApi({
      filings: [unavailable(503), unavailable(503), unavailable(503), filing('COMPLETED')],
      reports: [unavailable(503), unavailable(503), unavailable(503), REPORT],
    });
    assert.equal((await run(api).promise).outcome, OUTCOMES.completed);
  });

  it('respects a different configured limit', async () => {
    const api = scriptedApi({ filings: [filing('COMPLETED')], reports: [unavailable(503), REPORT] });
    const error = await rejection(run(api, {
      settings: { ...FAST_SETTINGS, maxConsecutiveTransientErrors: 0 },
    }).promise);
    assert.equal(error.status, 503);
    assert.match(error.problem.detail, /after 0 attempts/);
  });

  it('still fails immediately on 500 and other non-transient statuses', async () => {
    for (const status of [500, 501, 400, 403]) {
      const api = scriptedApi({ filings: [filing('COMPLETED')], reports: [httpFailure(status, { title: 'x' })] });
      assert.equal((await rejection(run(api).promise)).status, status);
      assert.equal(api.calls.getReport, 1);
    }
  });

  it('handles nginx 503 problem+json and 502 HTML pages through the real API client', async () => {
    const { fetchFn } = scriptedFetch([
      jsonResponse(200, filing('COMPLETED')),
      problemResponse(503, { title: 'Service Unavailable', status: 503, detail: 'Reporting is restarting' }),
      textResponse(502, '<html>502 Bad Gateway</html>'),
      textResponse(504, '<html>504 Gateway Time-out</html>', 'text/html'),
      problemResponse(404, { title: 'Not Found', status: 404 }),
      jsonResponse(200, REPORT),
    ]);
    const result = await run(createApiClient({ fetchFn })).promise;
    assert.equal(result.outcome, OUTCOMES.completed);
  });
});

describe('realSleep', () => {
  it('resolves after the given delay', async () => {
    const started = Date.now();
    await realSleep(10);
    assert.ok(Date.now() - started >= 5);
  });
});
