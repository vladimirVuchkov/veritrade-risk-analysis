import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { describeStatus } from '../js/format.js';
import { createFlowSession, createLatestOnly, MESSAGES } from '../js/flow-session.js';
import { FAST_SETTINGS, filing, httpFailure, recordingSleep } from './support/fakes.js';

const A = '0a0a0a0a-0000-4000-8000-00000000000a';
const B = '0b0b0b0b-0000-4000-8000-00000000000b';
const reportOf = (filingId) => ({ filingId, status: 'COMPLETED', findings: [], summary: null });

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

const tick = () => new Promise(setImmediate);

function controllableApi() {
  const pending = [];
  const request = (method, filingId, signal) => {
    const handle = { method, filingId, signal, ...deferred() };
    pending.push(handle);
    return handle.promise;
  };
  return {
    api: {
      getFiling: (filingId, { signal } = {}) => request('getFiling', filingId, signal),
      getReport: (filingId, { signal } = {}) => request('getReport', filingId, signal),
      submitFiling: () => request('submitFiling', null, undefined),
    },
    pending,
    async take(method, filingId = null) {
      for (let round = 0; round < 50; round += 1) {
        const index = pending.findIndex((item) => item.method === method && item.filingId === filingId);
        if (index >= 0) return pending.splice(index, 1)[0];
        await tick();
      }
      throw new Error(`No pending ${method} for ${filingId}`);
    },
  };
}

function recordingView() {
  const log = [];
  return {
    log,
    view: {
      progress: (tone, message) => log.push(['progress', tone, message]),
      clearProgress: () => log.push(['clearProgress']),
      clearReport: () => log.push(['clearReport']),
      report: (report) => log.push(['report', report.filingId]),
      settled: () => log.push(['settled']),
    },
    since(index) {
      return log.slice(index);
    },
  };
}

function session() {
  const server = controllableApi();
  const recorder = recordingView();
  const flows = createFlowSession({
    api: server.api, view: recorder.view, settings: FAST_SETTINGS, sleep: recordingSleep().sleep,
  });
  return { server, recorder, flows };
}

async function completeFlow(server, filingId) {
  (await server.take('getFiling', filingId)).resolve(filing('COMPLETED', { filingId }));
  (await server.take('getReport', filingId)).resolve(reportOf(filingId));
}

const mentions = (entries, text) => entries.some((entry) => entry.some((part) => String(part).includes(text)));

describe('createLatestOnly', () => {
  it('aborts and invalidates the previous token when a new one starts', () => {
    const latest = createLatestOnly();
    const first = latest.next();
    assert.equal(first.isCurrent(), true);
    const second = latest.next();
    assert.equal(first.isCurrent(), false);
    assert.equal(first.signal.aborted, true);
    assert.equal(second.isCurrent(), true);
    assert.equal(second.signal.aborted, false);
  });
});

describe('flow session when filings are switched', () => {
  it('ignores a late status for the previous filing and aborts its request', async () => {
    const { server, recorder, flows } = session();
    const first = flows.follow(A);
    const lateStatus = await server.take('getFiling', A);
    const switchedAt = recorder.log.length;
    const second = flows.follow(B);
    assert.equal(lateStatus.signal.aborted, true);

    lateStatus.resolve(filing('ANALYZING', { filingId: A }));
    await first;
    assert.deepEqual(recorder.since(switchedAt), [
      ['clearReport'],
      ['progress', 'info', MESSAGES.checking],
    ]);

    await completeFlow(server, B);
    await second;
    const after = recorder.since(switchedAt);
    assert.ok(!mentions(after, describeStatus('ANALYZING')), JSON.stringify(after));
    assert.deepEqual(after.at(-2), ['report', B]);
    assert.deepEqual(after.at(-1), ['settled']);
  });

  it('ignores a late terminal status for the previous filing even after the new one finished', async () => {
    const { server, recorder, flows } = session();
    const first = flows.follow(A);
    const lateStatus = await server.take('getFiling', A);
    const second = flows.follow(B);
    await completeFlow(server, B);
    await second;
    const finishedAt = recorder.log.length;

    lateStatus.resolve(filing('FAILED', { filingId: A, failureReason: 'old failure' }));
    await first;
    assert.deepEqual(recorder.since(finishedAt), []);
  });

  it('never renders a report that arrives for a filing no longer shown', async () => {
    const { server, recorder, flows } = session();
    const first = flows.follow(A);
    (await server.take('getFiling', A)).resolve(filing('COMPLETED', { filingId: A }));
    const lateReport = await server.take('getReport', A);
    const switchedAt = recorder.log.length;
    const second = flows.follow(B);

    lateReport.resolve(reportOf(A));
    await first;
    await completeFlow(server, B);
    await second;
    const after = recorder.since(switchedAt);
    assert.ok(!after.some((entry) => entry[0] === 'report' && entry[1] === A), JSON.stringify(after));
    assert.equal(after.filter((entry) => entry[0] === 'settled').length, 1);
  });

  it('never shows an error from the previous filing', async () => {
    const { server, recorder, flows } = session();
    const first = flows.follow(A);
    const lateStatus = await server.take('getFiling', A);
    const switchedAt = recorder.log.length;
    flows.follow(B);

    lateStatus.reject(httpFailure(500, { title: 'Internal Server Error', detail: 'old boom' }));
    await first;
    assert.ok(!mentions(recorder.since(switchedAt), 'old boom'));
  });
});

describe('flow session submissions', () => {
  it('ignores a second submit while the first is still being sent', async () => {
    const { server, flows } = session();
    const first = flows.submit({ companyName: 'A', title: 'T', content: 'x' });
    assert.equal(await flows.submit({ companyName: 'A', title: 'T', content: 'x' }), null);
    (await server.take('submitFiling')).resolve({ filingId: A, status: 'SUBMITTED' });
    const { accepted, flow } = await first;
    assert.equal(accepted.filingId, A);
    assert.equal(server.pending.filter((item) => item.method === 'submitFiling').length, 0);
    await completeFlow(server, A);
    await flow;
  });

  it('accepts a new submit once the previous one has been answered', async () => {
    const { server, flows } = session();
    const first = flows.submit({});
    (await server.take('submitFiling')).resolve({ filingId: A, status: 'SUBMITTED' });
    await first;
    const second = flows.submit({});
    (await server.take('submitFiling')).resolve({ filingId: B, status: 'SUBMITTED' });
    assert.equal((await second).accepted.filingId, B);
  });

  it('stops the running flow as soon as a new submit starts', async () => {
    const { server, recorder, flows } = session();
    const running = flows.follow(A);
    const lateStatus = await server.take('getFiling', A);
    const submitted = flows.submit({});
    assert.equal(lateStatus.signal.aborted, true);
    const submittingAt = recorder.log.length;

    lateStatus.resolve(filing('ANALYZING', { filingId: A }));
    await running;
    assert.deepEqual(recorder.since(submittingAt), []);
    assert.deepEqual(recorder.log.at(-1), ['progress', 'info', MESSAGES.submitting]);

    (await server.take('submitFiling')).resolve({ filingId: B, status: 'SUBMITTED' });
    const { flow } = await submitted;
    await completeFlow(server, B);
    await flow;
    assert.deepEqual(recorder.log.at(-2), ['report', B]);
  });

  it('does not follow a submitted filing when another filing was opened meanwhile', async () => {
    const { server, recorder, flows } = session();
    const submitted = flows.submit({});
    const sending = await server.take('submitFiling');
    const opened = flows.follow(B);
    const openedAt = recorder.log.length;

    sending.resolve({ filingId: A, status: 'SUBMITTED' });
    const { accepted, flow } = await submitted;
    assert.equal(accepted.filingId, A);
    assert.equal(flow, null);
    assert.deepEqual(recorder.since(openedAt), []);

    await completeFlow(server, B);
    await opened;
    assert.ok(!server.pending.some((item) => item.filingId === A));
    assert.deepEqual(recorder.log.at(-2), ['report', B]);
  });

  it('clears the submitting line when the submit fails, unless something else is shown', async () => {
    const { server, recorder, flows } = session();
    const failing = flows.submit({});
    (await server.take('submitFiling')).reject(httpFailure(400, { title: 'Bad Request' }));
    await assert.rejects(failing);
    assert.deepEqual(recorder.log.at(-1), ['clearProgress']);

    const superseded = flows.submit({});
    const sending = await server.take('submitFiling');
    flows.follow(B);
    const openedAt = recorder.log.length;
    sending.reject(httpFailure(400, { title: 'Bad Request' }));
    await assert.rejects(superseded);
    assert.deepEqual(recorder.since(openedAt), []);
  });
});
