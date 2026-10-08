import { ApiError, ERROR_KINDS } from '../../js/api.js';
import { CONFIG } from '../../js/config.js';

export function jsonResponse(status, body, contentType = 'application/json') {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
}

export function problemResponse(status, problem) {
  return jsonResponse(status, problem, 'application/problem+json');
}

export function textResponse(status, text, contentType = 'text/html') {
  return new Response(text, { status, headers: { 'Content-Type': contentType } });
}

export function scriptedFetch(steps) {
  const calls = [];
  const queue = [...steps];
  const fetchFn = async (url, init = {}) => {
    calls.push({ url, init });
    const step = queue.shift();
    if (step === undefined) throw new Error(`Unexpected fetch: ${url}`);
    if (step instanceof Error) throw step;
    return typeof step === 'function' ? step(url, init) : step;
  };
  return { fetchFn, calls };
}

export function networkFailure() {
  return new ApiError({ kind: ERROR_KINDS.network, problem: { title: 'Network error', detail: 'down' } });
}

export function httpFailure(status, problem) {
  return new ApiError({ kind: ERROR_KINDS.http, status, problem });
}

function nextFrom(queue, name) {
  if (queue.length === 0) throw new Error(`No scripted value left for ${name}`);
  const value = queue.length > 1 ? queue.shift() : queue[0];
  if (value instanceof Error) throw value;
  return value;
}

export function scriptedApi({ filings = [], reports = [] }) {
  const calls = { getFiling: 0, getReport: 0 };
  const filingQueue = [...filings];
  const reportQueue = [...reports];
  return {
    calls,
    async getFiling() {
      calls.getFiling += 1;
      return nextFrom(filingQueue, 'getFiling');
    },
    async getReport() {
      calls.getReport += 1;
      return nextFrom(reportQueue, 'getReport');
    },
  };
}

export function recordingSleep() {
  const delays = [];
  const sleep = async (ms) => {
    delays.push(ms);
  };
  return { sleep, delays };
}

export function filing(status, extra = {}) {
  return {
    filingId: '3f2b8c1e-6a4d-4e2f-9b7a-1c5d8e9f0a12',
    companyName: 'Acme Holdings Inc.',
    title: 'Form 10-K',
    status,
    submittedAt: '2026-10-07T12:00:00Z',
    failureReason: null,
    ...extra,
  };
}

export const FAST_SETTINGS = Object.freeze({
  ...CONFIG.polling,
  intervalMs: 5,
  statusMaxAttempts: 5,
  reportMaxAttempts: 5,
  maxConsecutiveTransientErrors: 3,
});
