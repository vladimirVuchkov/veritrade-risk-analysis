import { CONFIG } from './config.js';

const HTTP_NOT_FOUND = 404;

export const ERROR_KINDS = Object.freeze({
  http: 'http',
  network: 'network',
  aborted: 'aborted',
  invalidResponse: 'invalid-response',
});

export class ApiError extends Error {
  constructor({ kind, status = null, problem }) {
    super(problem.detail || problem.title);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = status;
    this.problem = problem;
  }
}

function isJsonContentType(contentType) {
  return /\bjson\b/i.test(contentType || '');
}

function fallbackProblem(response) {
  return {
    title: response.statusText || 'Request failed',
    status: response.status,
    detail: `The server returned an unexpected response (HTTP ${response.status}).`,
  };
}

function normaliseProblem(body, response) {
  if (body === null || typeof body !== 'object' || Array.isArray(body)) {
    return fallbackProblem(response);
  }
  const fallback = fallbackProblem(response);
  return {
    ...body,
    title: typeof body.title === 'string' && body.title ? body.title : fallback.title,
    status: Number.isInteger(body.status) ? body.status : response.status,
    detail: typeof body.detail === 'string' ? body.detail : undefined,
  };
}

export async function problemFromResponse(response) {
  if (!isJsonContentType(response.headers.get('content-type'))) {
    return fallbackProblem(response);
  }
  try {
    return normaliseProblem(await response.json(), response);
  } catch {
    return fallbackProblem(response);
  }
}

async function readJson(response) {
  try {
    return await response.json();
  } catch {
    throw new ApiError({
      kind: ERROR_KINDS.invalidResponse,
      status: response.status,
      problem: { title: 'Invalid response', detail: 'The server response could not be read.' },
    });
  }
}

function abortedError(cause) {
  const error = new ApiError({
    kind: ERROR_KINDS.aborted,
    problem: { title: 'Request cancelled', detail: 'The request was cancelled.' },
  });
  error.cause = cause;
  return error;
}

function networkError(cause) {
  if (cause?.name === 'AbortError') return abortedError(cause);
  const error = new ApiError({
    kind: ERROR_KINDS.network,
    problem: {
      title: 'Network error',
      detail: 'The server could not be reached. Check your connection and try again.',
    },
  });
  error.cause = cause;
  return error;
}

export function clampListLimit(limit) {
  const { minListLimit, maxListLimit } = CONFIG.limits;
  const whole = Number.isFinite(limit) ? Math.trunc(limit) : CONFIG.ui.recentFilingsLimit;
  return Math.min(maxListLimit, Math.max(minListLimit, whole));
}

export function createApiClient({ fetchFn, baseUrl = '' }) {
  const { filingsPath, reportsPath } = CONFIG.api;

  async function send(path, init) {
    try {
      return await fetchFn(`${baseUrl}${path}`, init);
    } catch (cause) {
      throw networkError(cause);
    }
  }

  async function expectSuccess(response) {
    if (!response.ok) {
      throw new ApiError({
        kind: ERROR_KINDS.http,
        status: response.status,
        problem: await problemFromResponse(response),
      });
    }
    return readJson(response);
  }

  const jsonGet = { method: 'GET', headers: { Accept: 'application/json' } };
  const filingPath = (id) => `${filingsPath}/${encodeURIComponent(id)}`;

  return {
    async submitFiling(request) {
      const response = await send(filingsPath, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(request),
      });
      return expectSuccess(response);
    },
    async getFiling(filingId, { signal } = {}) {
      return expectSuccess(await send(filingPath(filingId), { ...jsonGet, signal }));
    },
    async getReport(filingId, { signal } = {}) {
      const response = await send(`${reportsPath}/${encodeURIComponent(filingId)}`, { ...jsonGet, signal });
      if (response.status === HTTP_NOT_FOUND) {
        return null;
      }
      return expectSuccess(response);
    },
    async listFilings(limit) {
      const query = `?limit=${clampListLimit(limit)}`;
      return expectSuccess(await send(`${filingsPath}${query}`, jsonGet));
    },
  };
}
