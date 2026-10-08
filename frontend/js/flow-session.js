import { CONFIG } from './config.js';
import { describeOutcome, describeStatus, problemMessage } from './format.js';
import { OUTCOMES, realSleep, runAnalysisFlow } from './polling.js';

export const MESSAGES = Object.freeze({
  submitting: 'Submitting the filing…',
  checking: 'Checking the filing status…',
});

export function createLatestOnly() {
  let generation = 0;
  let controller = null;
  return {
    next() {
      generation += 1;
      controller?.abort();
      controller = new AbortController();
      const mine = generation;
      const { signal } = controller;
      return { signal, isCurrent: () => mine === generation && !signal.aborted };
    },
  };
}

export function createFlowSession({ api, view, settings = CONFIG.polling, sleep = realSleep }) {
  const latest = createLatestOnly();
  let submitting = false;

  const guard = (token, show) => (...args) => {
    if (token.isCurrent()) show(...args);
  };

  function showOutcome(result) {
    const { tone, message } = describeOutcome(result);
    view.progress(tone, message);
    if (result.outcome === OUTCOMES.completed) view.report(result.report, result.filing);
  }

  async function run(filingId, token) {
    try {
      const result = await runAnalysisFlow({
        api,
        filingId,
        sleep,
        settings,
        signal: token.signal,
        isCancelled: () => !token.isCurrent(),
        onStatus: guard(token, (filing) => view.progress('info', describeStatus(filing.status))),
      });
      guard(token, showOutcome)(result);
    } catch (error) {
      guard(token, view.progress)('error', problemMessage(error.problem));
    }
    guard(token, view.settled)();
  }

  function start(message) {
    const token = latest.next();
    view.clearReport();
    view.progress('info', message);
    return token;
  }

  return {
    follow(filingId) {
      return run(filingId, start(MESSAGES.checking));
    },
    async submit(request) {
      if (submitting) return null;
      submitting = true;
      const token = start(MESSAGES.submitting);
      try {
        const accepted = await api.submitFiling(request);
        if (!token.isCurrent()) return { accepted, flow: null };
        view.progress('info', MESSAGES.checking);
        return { accepted, flow: run(accepted.filingId, token) };
      } catch (error) {
        guard(token, view.clearProgress)();
        throw error;
      } finally {
        submitting = false;
      }
    },
  };
}
