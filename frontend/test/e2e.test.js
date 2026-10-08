import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { ApiError, createApiClient, ERROR_KINDS } from '../js/api.js';
import { CATEGORIES, CONFIG } from '../js/config.js';
import { createFlowSession } from '../js/flow-session.js';
import { describeOutcome, problemMessage } from '../js/format.js';
import { OUTCOMES, realSleep, runAnalysisFlow } from '../js/polling.js';
import { renderFilingsList, renderReport } from '../js/render.js';
import { SAMPLE_FILING } from '../js/sample.js';
import { MOCK_DEFAULTS } from '../mock/mock-config.js';
import { createMockServer } from '../mock/server.js';
import { byTag, createFakeDocument, serialize } from './support/fake-dom.js';

const TIMINGS = { submittedMs: 60, analyzingMs: 80, reportDelayMs: 120 };
const SETTINGS = { ...CONFIG.polling, intervalMs: 20, statusMaxAttempts: 100, reportMaxAttempts: 100, maxConsecutiveTransientErrors: 3 };
const doc = createFakeDocument();

function listen(server) {
  return new Promise((resolveListen) => {
    server.listen(0, '127.0.0.1', () => resolveListen(`http://127.0.0.1:${server.address().port}`));
  });
}

async function rejection(promise) {
  try {
    await promise;
  } catch (error) {
    return error;
  }
  throw new Error('Expected a rejection');
}

function trackingApi(api) {
  const seen = { reportMisses: 0, reportHits: 0 };
  return {
    seen,
    ...api,
    async getReport(filingId) {
      const report = await api.getReport(filingId);
      if (report === null) seen.reportMisses += 1;
      else seen.reportHits += 1;
      return report;
    },
  };
}

describe('end to end against the mock server', () => {
  const server = createMockServer({ timings: TIMINGS });
  let baseUrl;
  let api;

  const flow = (filingId, overrides = {}) => {
    const statuses = [];
    const tracked = trackingApi(api);
    const promise = runAnalysisFlow({
      api: tracked,
      filingId,
      sleep: realSleep,
      settings: SETTINGS,
      onStatus: (filing) => statuses.push(filing.status),
      ...overrides,
    });
    return { promise, statuses, seen: tracked.seen };
  };

  before(async () => {
    baseUrl = await listen(server);
    api = createApiClient({ fetchFn: fetch, baseUrl });
  });

  after(() => new Promise((resolveClose) => {
    server.closeAllConnections();
    server.close(resolveClose);
  }));

  it('submits the sample, follows the status, waits out the report 404 and renders the report', async () => {
    const accepted = await api.submitFiling(SAMPLE_FILING);
    assert.equal(accepted.status, 'SUBMITTED');
    const { promise, statuses, seen } = flow(accepted.filingId);
    const result = await promise;
    assert.equal(result.outcome, OUTCOMES.completed);
    assert.deepEqual([...new Set(statuses)], ['SUBMITTED', 'ANALYZING', 'COMPLETED']);
    assert.ok(seen.reportMisses >= 1, 'the report should be 404 at first');
    assert.equal(seen.reportHits, 1);

    const { report } = result;
    assert.equal(report.filingId, accepted.filingId);
    assert.equal(report.status, 'COMPLETED');
    assert.equal(report.summary.overallRiskLevel, 'CRITICAL');
    assert.equal(report.summary.totalFindings, report.findings.length);
    assert.deepEqual(new Set(report.findings.map((finding) => finding.category)), new Set(CATEGORIES));

    const view = renderReport(doc, report, result.filing);
    const marks = byTag(view, 'mark');
    assert.equal(marks.length, report.findings.length);
    marks.forEach((mark, index) => assert.equal(mark.textContent, report.findings[index].matchedText));
  });

  it('returns 202 with Location and echoes the correlation id', async () => {
    const response = await fetch(`${baseUrl}/api/filings`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Correlation-Id': 'corr-123' },
      body: JSON.stringify({ companyName: 'Acme', title: 'T', content: 'text' }),
    });
    const body = await response.json();
    assert.equal(response.status, 202);
    assert.equal(response.headers.get('location'), `/api/filings/${body.filingId}`);
    assert.equal(response.headers.get('x-correlation-id'), 'corr-123');
  });

  it('reports NONE for a filing without findings', async () => {
    const accepted = await api.submitFiling({ companyName: 'Calm Co', title: 'Quiet year', content: 'Revenue grew.' });
    const result = await flow(accepted.filingId).promise;
    assert.equal(result.report.summary.overallRiskLevel, 'NONE');
    assert.deepEqual(result.report.findings, []);
    const view = renderReport(doc, result.report, result.filing);
    assert.match(view.textContent, /No risk findings were detected/);
  });

  it('follows a filing to FAILED and shows the reason', async () => {
    const accepted = await api.submitFiling({ ...SAMPLE_FILING, title: `Broken ${MOCK_DEFAULTS.markers.fail}` });
    const { promise, statuses } = flow(accepted.filingId);
    const result = await promise;
    assert.equal(result.outcome, OUTCOMES.failed);
    assert.equal(statuses.at(-1), 'FAILED');
    assert.equal(result.reason, MOCK_DEFAULTS.failureReason);
    assert.equal(describeOutcome(result).message, `Analysis failed: ${MOCK_DEFAULTS.failureReason}`);

    await realSleep(TIMINGS.reportDelayMs);
    const report = await api.getReport(accepted.filingId);
    assert.equal(report.status, 'FAILED');
    assert.equal(report.failureReason, MOCK_DEFAULTS.failureReason);
  });

  it('times out with a clear outcome when the analysis takes too long', async () => {
    const accepted = await api.submitFiling({ companyName: 'Slow', title: 'Slow', content: 'text' });
    const result = await flow(accepted.filingId, {
      settings: { ...SETTINGS, statusMaxAttempts: 2, intervalMs: 1 },
    }).promise;
    assert.equal(result.outcome, OUTCOMES.timeout);
    assert.match(describeOutcome(result).message, /did not finish/);
  });

  describe('400 ProblemDetail responses', () => {
    async function expectBadRequest(promise, detailPattern) {
      const error = await rejection(promise);
      assert.ok(error instanceof ApiError);
      assert.equal(error.status, 400);
      assert.equal(error.problem.status, 400);
      assert.equal(error.problem.title, 'Bad Request');
      assert.match(error.problem.detail, detailPattern);
      assert.match(problemMessage(error.problem), /^Bad Request: /);
    }

    it('rejects blank fields', async () => {
      await expectBadRequest(api.submitFiling({ companyName: ' ', title: '', content: '\n' }), /Company name is required/);
    });

    it('rejects content of 2 MB + 1 byte made of multibyte characters', async () => {
      const content = '€'.repeat(699_050) + 'abc';
      await expectBadRequest(api.submitFiling({ companyName: 'A', title: 'T', content }), /2097153 bytes/);
    });

    it('accepts content of exactly 2 MB', async () => {
      const content = '€'.repeat(699_050) + 'ab';
      const accepted = await api.submitFiling({ companyName: 'A', title: 'T', content });
      assert.equal(accepted.status, 'SUBMITTED');
    });

    it('rejects invalid JSON and non-object bodies', async () => {
      for (const body of ['{not json', '[1,2]', 'null']) {
        const response = await fetch(`${baseUrl}/api/filings`, { method: 'POST', body });
        assert.equal(response.status, 400);
        assert.equal(response.headers.get('content-type'), 'application/problem+json');
      }
    });

    it('rejects an invalid list limit and filing id', async () => {
      for (const query of ['limit=0', 'limit=101', 'limit=abc', 'limit=1.5', 'limit=']) {
        const response = await fetch(`${baseUrl}/api/filings?${query}`);
        assert.equal(response.status, 400, query);
      }
      await expectBadRequest(api.getFiling('not-a-uuid'), /UUID/);
    });
  });

  it('returns 404 ProblemDetail for an unknown filing and keeps reports at 404', async () => {
    const unknown = '00000000-0000-4000-8000-000000000000';
    const error = await rejection(api.getFiling(unknown));
    assert.equal(error.status, 404);
    assert.match(error.problem.detail, /was not found/);
    assert.equal(await api.getReport(unknown), null);
  });

  it('shows a readable message for a non-JSON 413 page', async () => {
    const content = 'a'.repeat(MOCK_DEFAULTS.maxRequestBytes + 1);
    const error = await rejection(api.submitFiling({ companyName: 'A', title: 'T', content }));
    assert.equal(error.status, 413);
    assert.match(problemMessage(error.problem), /HTTP 413/);
  });

  it('surfaces a 500 ProblemDetail', async () => {
    const error = await rejection(api.submitFiling({
      companyName: 'A', title: `Boom ${MOCK_DEFAULTS.markers.serverError}`, content: 'text',
    }));
    assert.equal(error.status, 500);
    assert.equal(problemMessage(error.problem), 'Internal Server Error: Simulated server error.');
  });

  it('rejects unsupported methods with 405', async () => {
    const response = await fetch(`${baseUrl}/api/filings`, { method: 'DELETE' });
    assert.equal(response.status, 405);
  });

  it('lists recent filings newest first with the limit', async () => {
    const first = await api.submitFiling({ companyName: 'First', title: 'one', content: 'text' });
    const second = await api.submitFiling({ companyName: 'Second', title: 'two', content: 'text' });
    const filings = await api.listFilings(2);
    assert.deepEqual(filings.map((item) => item.filingId), [second.filingId, first.filingId]);
    const all = await api.listFilings(CONFIG.limits.maxListLimit);
    assert.ok(all.length > 2);
    for (const item of all) {
      assert.deepEqual(Object.keys(item).sort(), [
        'companyName', 'failureReason', 'filingId', 'status', 'submittedAt', 'title',
      ]);
    }
  });

  it('keeps hostile input inert from submission to rendering', async () => {
    const hostile = '<script>alert(1)</script><img src=x onerror=alert(2)>';
    const accepted = await api.submitFiling({
      companyName: hostile,
      title: hostile,
      content: `${hostile} We face pending litigation ${hostile}`,
    });
    const result = await flow(accepted.filingId).promise;
    const view = renderReport(doc, result.report, result.filing);
    const list = renderFilingsList(doc, await api.listFilings(1), () => {});
    for (const node of [view, list]) {
      assert.equal(byTag(node, 'script').length, 0);
      assert.equal(byTag(node, 'img').length, 0);
      assert.ok(node.textContent.includes(hostile));
      assert.ok(!serialize(node).includes('<script>'));
    }
    assert.equal(byTag(view, 'mark')[0].textContent, 'pending litigation');
  });

  it('serves the UI files', async () => {
    const index = await fetch(`${baseUrl}/`);
    assert.equal(index.status, 200);
    assert.match(await index.text(), /VeriTrade Risk Analysis/);
    assert.equal(index.headers.get('content-security-policy'), "default-src 'self'");
    const script = await fetch(`${baseUrl}/js/app.js`);
    assert.match(script.headers.get('content-type'), /javascript/);
    assert.equal((await fetch(`${baseUrl}/missing.css`)).status, 404);
  });
});

describe('end to end with two overlapping flows', () => {
  const server = createMockServer({ timings: TIMINGS });
  let baseUrl;

  before(async () => {
    baseUrl = await listen(server);
  });

  after(() => new Promise((resolveClose) => {
    server.closeAllConnections();
    server.close(resolveClose);
  }));

  function holdFirstStatusRequest(filingId) {
    let release;
    const gate = new Promise((resolveGate) => {
      release = resolveGate;
    });
    const held = { issued: null, signal: null, release };
    let markIssued;
    held.issued = new Promise((resolveIssued) => {
      markIssued = resolveIssued;
    });
    let used = false;
    const fetchFn = async (url, init) => {
      if (used || !url.endsWith(`/api/filings/${filingId}`)) return fetch(url, init);
      used = true;
      held.signal = init.signal;
      markIssued();
      await gate;
      return fetch(url, { ...init, signal: undefined });
    };
    return { fetchFn, held };
  }

  function recordingView() {
    const log = [];
    return {
      log,
      view: {
        progress: (tone, message) => log.push({ kind: 'progress', tone, message }),
        clearProgress: () => log.push({ kind: 'clearProgress' }),
        clearReport: () => log.push({ kind: 'clearReport' }),
        report: (report) => log.push({ kind: 'report', filingId: report.filingId }),
        settled: () => log.push({ kind: 'settled' }),
      },
    };
  }

  it('shows only the second filing when a slow response for the first arrives late', async () => {
    const plainApi = createApiClient({ fetchFn: fetch, baseUrl });
    const first = await plainApi.submitFiling({ ...SAMPLE_FILING, title: `First ${MOCK_DEFAULTS.markers.fail}` });
    const firstCreated = Date.now();
    const { fetchFn, held } = holdFirstStatusRequest(first.filingId);
    const { log, view } = recordingView();
    const session = createFlowSession({
      api: createApiClient({ fetchFn, baseUrl }), view, settings: SETTINGS, sleep: realSleep,
    });

    const firstFlow = session.follow(first.filingId);
    await held.issued;
    await realSleep(TIMINGS.submittedMs + TIMINGS.analyzingMs / 2);
    const secondStartedAt = log.length;
    const { accepted: second, flow: secondFlow } = await session.submit(SAMPLE_FILING);
    assert.equal(held.signal.aborted, true, 'the first flow request should be aborted');

    await realSleep(Math.max(0, firstCreated + TIMINGS.submittedMs + TIMINGS.analyzingMs + 10 - Date.now()));
    assert.equal((await plainApi.getFiling(first.filingId)).status, 'FAILED');
    assert.notEqual((await plainApi.getFiling(second.filingId)).status, 'COMPLETED');
    held.release();
    await firstFlow;
    await secondFlow;

    const shown = log.slice(secondStartedAt);
    const text = JSON.stringify(shown);
    assert.doesNotMatch(text, /failed/i, text);
    assert.ok(!text.includes(MOCK_DEFAULTS.failureReason));
    assert.ok(!text.includes(describeOutcome({ outcome: OUTCOMES.cancelled }).message), text);
    assert.deepEqual(shown.filter((entry) => entry.kind === 'report'), [{ kind: 'report', filingId: second.filingId }]);
    const lastProgress = shown.filter((entry) => entry.kind === 'progress').at(-1);
    assert.equal(lastProgress.message, describeOutcome({ outcome: OUTCOMES.completed }).message);
    assert.deepEqual(shown.at(-1), { kind: 'settled' });
    assert.equal(shown.filter((entry) => entry.kind === 'settled').length, 1);
  });
});

describe('end to end with Reporting temporarily unavailable', () => {
  const limit = SETTINGS.maxConsecutiveTransientErrors;
  const servers = [];

  async function start(unavailableReportRequests) {
    const server = createMockServer({ timings: TIMINGS, unavailableReportRequests });
    servers.push(server);
    return createApiClient({ fetchFn: fetch, baseUrl: await listen(server) });
  }

  async function submitAndWaitForReportReadiness(api) {
    const accepted = await api.submitFiling(SAMPLE_FILING);
    await realSleep(TIMINGS.submittedMs + TIMINGS.analyzingMs + TIMINGS.reportDelayMs);
    return accepted.filingId;
  }

  after(() => Promise.all(servers.map((server) => new Promise((resolveClose) => {
    server.closeAllConnections();
    server.close(resolveClose);
  }))));

  it('returns 503 problem+json for the first N report requests', async () => {
    const server = createMockServer({ timings: TIMINGS, unavailableReportRequests: 1 });
    servers.push(server);
    const baseUrl = await listen(server);
    const response = await fetch(`${baseUrl}/api/reports/00000000-0000-4000-8000-000000000000`);
    assert.equal(response.status, 503);
    assert.equal(response.headers.get('content-type'), 'application/problem+json');
    assert.equal((await response.json()).title, 'Service Unavailable');
    const next = await fetch(`${baseUrl}/api/reports/00000000-0000-4000-8000-000000000000`);
    assert.equal(next.status, 404);
  });

  it('keeps polling through 503 responses up to the limit and gets the report', async () => {
    const api = await start(limit);
    const filingId = await submitAndWaitForReportReadiness(api);
    const result = await runAnalysisFlow({ api, filingId, sleep: realSleep, settings: SETTINGS });
    assert.equal(result.outcome, OUTCOMES.completed);
    assert.equal(result.report.filingId, filingId);
  });

  it('gives up with a clear message when 503 lasts past the limit', async () => {
    const api = await start(limit + 1);
    const filingId = await submitAndWaitForReportReadiness(api);
    const error = await rejection(runAnalysisFlow({ api, filingId, sleep: realSleep, settings: SETTINGS }));
    assert.equal(error.status, 503);
    assert.match(problemMessage(error.problem), /^Service unavailable: .*HTTP 503.*did not recover/);
  });
});

describe('end to end without a server', () => {
  it('reports a network error when nothing is listening', async () => {
    const server = createMockServer();
    const baseUrl = await listen(server);
    await new Promise((resolveClose) => server.close(resolveClose));
    const api = createApiClient({ fetchFn: fetch, baseUrl });
    const error = await rejection(runAnalysisFlow({
      api,
      filingId: '00000000-0000-4000-8000-000000000000',
      sleep: realSleep,
      settings: SETTINGS,
    }));
    assert.equal(error.kind, ERROR_KINDS.network);
    assert.match(problemMessage(error.problem), /^Network error/);
  });
});
