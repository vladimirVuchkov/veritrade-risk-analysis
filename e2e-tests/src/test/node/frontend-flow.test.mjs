// The frontend's own API client and polling flow (frontend/js) against the real stack behind nginx.
// Run by FrontendFlowE2E:  E2E_BASE_URL=http://localhost:PORT E2E_REPOSITORY_ROOT=. node --test <this file>
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const BASE_URL = process.env.E2E_BASE_URL;
const ROOT = process.env.E2E_REPOSITORY_ROOT;
const FAST_POLL_INTERVAL_MS = 200;
const STATUS_ATTEMPTS = 150;
const REPORT_ATTEMPTS = 150;
const LIST_LIMIT = 10;
const UNKNOWN_ID = '00000000-0000-4000-8000-000000000000';

const frontendModule = (name) => import(pathToFileURL(path.join(ROOT, 'frontend', 'js', name)).href);
const { createApiClient, ApiError, ERROR_KINDS } = await frontendModule('api.js');
const { runAnalysisFlow, OUTCOMES, PHASES } = await frontendModule('polling.js');
const { CONFIG } = await frontendModule('config.js');

const api = createApiClient({ fetchFn: fetch, baseUrl: BASE_URL });
const settings = {
  ...CONFIG.polling,
  intervalMs: FAST_POLL_INTERVAL_MS,
  statusMaxAttempts: STATUS_ATTEMPTS,
  reportMaxAttempts: REPORT_ATTEMPTS,
};

async function demoFiling() {
  return JSON.parse(await readFile(path.join(ROOT, 'scripts', 'demo-filing.json'), 'utf8'));
}

test('submit, poll the status and fetch the report with the frontend modules', async () => {
  const submitted = await api.submitFiling(await demoFiling());
  assert.equal(submitted.status, 'SUBMITTED');
  const statuses = [];
  const phases = [];

  const result = await runAnalysisFlow({
    api,
    filingId: submitted.filingId,
    settings,
    onStatus: (filing) => statuses.push(filing.status),
    onPhase: (phase) => phases.push(phase),
  });

  assert.equal(result.outcome, OUTCOMES.completed);
  assert.equal(result.filing.status, 'COMPLETED');
  assert.deepEqual(phases, [PHASES.status, PHASES.report]);
  assert.equal(statuses.at(-1), 'COMPLETED');
  assert.equal(result.report.filingId, submitted.filingId);
  assert.ok(result.report.findings.length > 0);
  assert.equal(result.report.summary.totalFindings, result.report.findings.length);
});

test('a server-side validation error becomes an ApiError with the problem detail', async () => {
  await assert.rejects(api.submitFiling({ companyName: '', title: '', content: '' }), (error) => {
    assert.ok(error instanceof ApiError);
    assert.equal(error.kind, ERROR_KINDS.http);
    assert.equal(error.status, 400);
    assert.equal(error.problem.status, 400);
    return true;
  });
});

test('a report that does not exist yet is null, not an error', async () => {
  assert.equal(await api.getReport(UNKNOWN_ID), null);
});

test('the recent filings list includes a new filing', async () => {
  const submitted = await api.submitFiling(await demoFiling());

  const filings = await api.listFilings(LIST_LIMIT);

  assert.ok(filings.length <= LIST_LIMIT);
  assert.ok(filings.some((filing) => filing.filingId === submitted.filingId));
});
