import { CONFIG, TERMINAL_STATUSES } from './config.js';
import { ApiError, ERROR_KINDS } from './api.js';

export const OUTCOMES = Object.freeze({
  completed: 'COMPLETED',
  failed: 'FAILED',
  timeout: 'TIMEOUT',
  cancelled: 'CANCELLED',
});

export const PHASES = Object.freeze({ status: 'status', report: 'report' });

export function realSleep(ms, signal) {
  return new Promise((resolve) => {
    if (signal?.aborted) {
      resolve();
      return;
    }
    const wake = () => {
      clearTimeout(timer);
      signal?.removeEventListener('abort', wake);
      resolve();
    };
    const timer = setTimeout(wake, ms);
    signal?.addEventListener('abort', wake, { once: true });
  });
}

function isTransient(error, settings) {
  if (error?.kind === ERROR_KINDS.network) return true;
  return error?.kind === ERROR_KINDS.http && settings.transientHttpStatuses.includes(error.status);
}

function gaveUp(error, settings) {
  if (error.kind === ERROR_KINDS.network) return error;
  const exhausted = new ApiError({
    kind: error.kind,
    status: error.status,
    problem: {
      ...error.problem,
      title: 'Service unavailable',
      detail: `The service is temporarily unavailable (HTTP ${error.status}) and did not recover after `
        + `${settings.maxConsecutiveTransientErrors} attempts in a row. Please try again later.`,
    },
  });
  exhausted.cause = error;
  return exhausted;
}

async function attemptOnce(fetchOnce, transientState, settings) {
  try {
    const value = await fetchOnce();
    transientState.consecutiveErrors = 0;
    return { ok: true, value };
  } catch (error) {
    if (!isTransient(error, settings)) throw error;
    transientState.consecutiveErrors += 1;
    if (transientState.consecutiveErrors > settings.maxConsecutiveTransientErrors) {
      throw gaveUp(error, settings);
    }
    return { ok: false };
  }
}

async function attemptUnlessCancelled(fetchOnce, transientState, settings, isCancelled) {
  try {
    const result = await attemptOnce(fetchOnce, transientState, settings);
    return isCancelled() ? null : result;
  } catch (error) {
    if (isCancelled()) return null;
    throw error;
  }
}

export async function pollUntil({
  fetchOnce, isDone, maxAttempts, sleep, isCancelled, signal, onValue, settings,
}) {
  const transientState = { consecutiveErrors: 0 };
  let last = null;
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    if (isCancelled()) return { outcome: OUTCOMES.cancelled, last };
    const result = await attemptUnlessCancelled(fetchOnce, transientState, settings, isCancelled);
    if (result === null) return { outcome: OUTCOMES.cancelled, last };
    if (result.ok) {
      last = result.value;
      onValue(result.value);
      if (isDone(result.value)) return { done: true, last };
    }
    if (attempt < maxAttempts) await sleep(settings.intervalMs, signal);
  }
  return { outcome: isCancelled() ? OUTCOMES.cancelled : OUTCOMES.timeout, last };
}

function isTerminal(filing) {
  return TERMINAL_STATUSES.includes(filing?.status);
}

export function pollFilingStatus({ api, filingId, sleep, isCancelled, signal, onStatus, settings }) {
  return pollUntil({
    fetchOnce: () => api.getFiling(filingId, { signal }),
    isDone: isTerminal,
    maxAttempts: settings.statusMaxAttempts,
    sleep,
    isCancelled,
    signal,
    onValue: onStatus,
    settings,
  });
}

export function pollReport({ api, filingId, sleep, isCancelled, signal, settings }) {
  return pollUntil({
    fetchOnce: () => api.getReport(filingId, { signal }),
    isDone: (report) => report !== null,
    maxAttempts: settings.reportMaxAttempts,
    sleep,
    isCancelled,
    signal,
    onValue: () => {},
    settings,
  });
}

export async function runAnalysisFlow({
  api,
  filingId,
  sleep = realSleep,
  isCancelled = () => false,
  signal,
  onStatus = () => {},
  onPhase = () => {},
  settings = CONFIG.polling,
}) {
  const cancelled = () => Boolean(signal?.aborted) || isCancelled();
  const common = { api, filingId, sleep, isCancelled: cancelled, signal, settings };
  onPhase(PHASES.status);
  const status = await pollFilingStatus({ ...common, onStatus });
  if (!status.done) return { outcome: status.outcome, phase: PHASES.status, filing: status.last };
  const filing = status.last;
  if (filing.status === OUTCOMES.failed) {
    return { outcome: OUTCOMES.failed, filing, reason: filing.failureReason ?? null };
  }
  onPhase(PHASES.report);
  const report = await pollReport(common);
  if (!report.done) return { outcome: report.outcome, phase: PHASES.report, filing };
  return { outcome: OUTCOMES.completed, filing, report: report.last };
}
