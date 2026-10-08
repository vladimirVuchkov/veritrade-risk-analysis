import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { createApiClient, ERROR_KINDS } from '../js/api.js';
import { OUTCOMES, realSleep, runAnalysisFlow } from '../js/polling.js';
import { FAST_SETTINGS, filing, jsonResponse, recordingSleep } from './support/fakes.js';

const ID = '3f2b8c1e-6a4d-4e2f-9b7a-1c5d8e9f0a12';
const REPORT = { filingId: ID, status: 'COMPLETED', findings: [], summary: null };

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function abortError() {
  return new DOMException('The operation was aborted.', 'AbortError');
}

function pendingUntilAborted(signal) {
  return new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => reject(abortError()), { once: true });
  });
}

describe('cancellation in runAnalysisFlow', () => {
  it('ignores a status response that arrives after the flow was cancelled', async () => {
    const late = deferred();
    let cancelled = false;
    const statuses = [];
    const api = { getFiling: () => late.promise, getReport: async () => REPORT };
    const promise = runAnalysisFlow({
      api,
      filingId: ID,
      sleep: recordingSleep().sleep,
      settings: FAST_SETTINGS,
      isCancelled: () => cancelled,
      onStatus: (value) => statuses.push(value.status),
    });
    cancelled = true;
    late.resolve(filing('ANALYZING'));
    const result = await promise;
    assert.equal(result.outcome, OUTCOMES.cancelled);
    assert.deepEqual(statuses, []);
  });

  it('ignores a late terminal status and never asks for the report', async () => {
    const late = deferred();
    let cancelled = false;
    let reportCalls = 0;
    const statuses = [];
    const api = {
      getFiling: () => late.promise,
      getReport: async () => {
        reportCalls += 1;
        return REPORT;
      },
    };
    const promise = runAnalysisFlow({
      api,
      filingId: ID,
      sleep: recordingSleep().sleep,
      settings: FAST_SETTINGS,
      isCancelled: () => cancelled,
      onStatus: (value) => statuses.push(value.status),
    });
    cancelled = true;
    late.resolve(filing('COMPLETED'));
    const result = await promise;
    assert.equal(result.outcome, OUTCOMES.cancelled);
    assert.deepEqual(statuses, []);
    assert.equal(reportCalls, 0);
  });

  it('ignores a late report response after cancellation', async () => {
    const late = deferred();
    let cancelled = false;
    const api = { getFiling: async () => filing('COMPLETED'), getReport: () => late.promise };
    const promise = runAnalysisFlow({
      api,
      filingId: ID,
      sleep: recordingSleep().sleep,
      settings: FAST_SETTINGS,
      isCancelled: () => cancelled,
    });
    await new Promise(setImmediate);
    cancelled = true;
    late.resolve(REPORT);
    const result = await promise;
    assert.equal(result.outcome, OUTCOMES.cancelled);
    assert.equal(result.report, undefined);
  });

  it('passes the abort signal to every request', async () => {
    const controller = new AbortController();
    const signals = [];
    const api = {
      getFiling: async (filingId, options) => {
        signals.push(options?.signal);
        return filing('COMPLETED');
      },
      getReport: async (filingId, options) => {
        signals.push(options?.signal);
        return REPORT;
      },
    };
    const result = await runAnalysisFlow({
      api, filingId: ID, sleep: recordingSleep().sleep, settings: FAST_SETTINGS, signal: controller.signal,
    });
    assert.equal(result.outcome, OUTCOMES.completed);
    assert.deepEqual(signals, [controller.signal, controller.signal]);
  });

  it('stops polling once the signal is aborted', async () => {
    const controller = new AbortController();
    const statuses = [];
    let calls = 0;
    const api = {
      getFiling: async () => {
        calls += 1;
        return filing('SUBMITTED');
      },
    };
    const result = await runAnalysisFlow({
      api,
      filingId: ID,
      sleep: recordingSleep().sleep,
      settings: FAST_SETTINGS,
      signal: controller.signal,
      onStatus: (value) => {
        statuses.push(value.status);
        controller.abort();
      },
    });
    assert.equal(result.outcome, OUTCOMES.cancelled);
    assert.equal(calls, 1);
    assert.deepEqual(statuses, ['SUBMITTED']);
  });

  it('reports an aborted in-flight request as cancelled, not as an error', async () => {
    const controller = new AbortController();
    const api = { getFiling: (filingId, { signal }) => pendingUntilAborted(signal) };
    const promise = runAnalysisFlow({
      api, filingId: ID, sleep: recordingSleep().sleep, settings: FAST_SETTINGS, signal: controller.signal,
    });
    controller.abort();
    assert.equal((await promise).outcome, OUTCOMES.cancelled);
  });

  it('aborts a real fetch through the API client', async () => {
    const controller = new AbortController();
    const seen = [];
    const fetchFn = (url, init) => {
      seen.push(init.signal);
      return pendingUntilAborted(init.signal);
    };
    const promise = runAnalysisFlow({
      api: createApiClient({ fetchFn }),
      filingId: ID,
      sleep: recordingSleep().sleep,
      settings: FAST_SETTINGS,
      signal: controller.signal,
    });
    controller.abort();
    assert.equal((await promise).outcome, OUTCOMES.cancelled);
    assert.deepEqual(seen, [controller.signal]);
  });
});

describe('cancellation in the API client', () => {
  it('passes the signal to fetch for status and report requests', async () => {
    const controller = new AbortController();
    const seen = [];
    const fetchFn = async (url, init) => {
      seen.push(init.signal);
      return jsonResponse(200, url.includes('/reports/') ? REPORT : filing('COMPLETED'));
    };
    const api = createApiClient({ fetchFn });
    await api.getFiling(ID, { signal: controller.signal });
    await api.getReport(ID, { signal: controller.signal });
    assert.deepEqual(seen, [controller.signal, controller.signal]);
  });

  it('marks an aborted request as aborted, not as a network error', async () => {
    const api = createApiClient({ fetchFn: async () => { throw abortError(); } });
    const error = await api.getFiling(ID).then(() => null, (thrown) => thrown);
    assert.equal(error.kind, ERROR_KINDS.aborted);
  });
});

describe('realSleep with a signal', () => {
  it('wakes up as soon as the signal is aborted', async () => {
    const controller = new AbortController();
    const started = Date.now();
    const sleeping = realSleep(10_000, controller.signal);
    controller.abort();
    await sleeping;
    assert.ok(Date.now() - started < 1000);
  });

  it('returns at once for a signal that is already aborted', async () => {
    const started = Date.now();
    await realSleep(10_000, AbortSignal.abort());
    assert.ok(Date.now() - started < 1000);
  });
});
