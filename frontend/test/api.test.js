import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { ApiError, clampListLimit, createApiClient, ERROR_KINDS, problemFromResponse } from '../js/api.js';
import { filing, jsonResponse, problemResponse, scriptedFetch, textResponse } from './support/fakes.js';

const ID = '3f2b8c1e-6a4d-4e2f-9b7a-1c5d8e9f0a12';
const badRequest = {
  type: 'about:blank', title: 'Bad Request', status: 400, detail: 'content must not be blank',
};

function client(steps, baseUrl) {
  const scripted = scriptedFetch(steps);
  return { api: createApiClient({ fetchFn: scripted.fetchFn, baseUrl }), calls: scripted.calls };
}

async function rejection(promise) {
  try {
    await promise;
  } catch (error) {
    return error;
  }
  throw new Error('Expected a rejection');
}

describe('submitFiling', () => {
  it('posts JSON to /api/filings and returns the accepted body', async () => {
    const { api, calls } = client([jsonResponse(202, { filingId: ID, status: 'SUBMITTED' })]);
    const request = { companyName: 'Acme', title: '10-K', content: 'text' };
    assert.deepEqual(await api.submitFiling(request), { filingId: ID, status: 'SUBMITTED' });
    assert.equal(calls[0].url, '/api/filings');
    assert.equal(calls[0].init.method, 'POST');
    assert.equal(calls[0].init.headers['Content-Type'], 'application/json');
    assert.deepEqual(JSON.parse(calls[0].init.body), request);
  });

  it('turns a 400 ProblemDetail into an ApiError with the server detail', async () => {
    const { api } = client([problemResponse(400, badRequest)]);
    const error = await rejection(api.submitFiling({}));
    assert.ok(error instanceof ApiError);
    assert.equal(error.kind, ERROR_KINDS.http);
    assert.equal(error.status, 400);
    assert.equal(error.problem.detail, 'content must not be blank');
    assert.equal(error.message, 'content must not be blank');
  });

  it('keeps extension members of the ProblemDetail', async () => {
    const { api } = client([problemResponse(400, { ...badRequest, errors: ['a'] })]);
    assert.deepEqual((await rejection(api.submitFiling({}))).problem.errors, ['a']);
  });

  it('turns a 500 ProblemDetail into an ApiError', async () => {
    const problem = { title: 'Internal Server Error', status: 500, detail: 'boom' };
    const error = await rejection(client([problemResponse(500, problem)]).api.submitFiling({}));
    assert.equal(error.status, 500);
    assert.equal(error.problem.title, 'Internal Server Error');
  });

  it('handles a non-JSON error body such as an nginx 413 page', async () => {
    const html = '<html><body><h1>413 Request Entity Too Large</h1></body></html>';
    const error = await rejection(client([textResponse(413, html)]).api.submitFiling({}));
    assert.equal(error.status, 413);
    assert.match(error.problem.detail, /unexpected response \(HTTP 413\)/);
    assert.ok(!JSON.stringify(error.problem).includes('<html>'));
  });

  it('handles a JSON content type with an unreadable body', async () => {
    const error = await rejection(client([textResponse(502, '{oops', 'application/problem+json')]).api.submitFiling({}));
    assert.equal(error.status, 502);
    assert.match(error.problem.detail, /HTTP 502/);
  });

  it('normalises problem bodies that are not objects or lack a title', async () => {
    const array = await rejection(client([problemResponse(400, ['x'])]).api.submitFiling({}));
    assert.match(array.problem.detail, /HTTP 400/);
    const noTitle = await rejection(client([problemResponse(400, { detail: 'd', status: 'x' })]).api.submitFiling({}));
    assert.equal(noTitle.problem.detail, 'd');
    assert.equal(noTitle.problem.status, 400);
    assert.equal(typeof noTitle.problem.title, 'string');
    const nullBody = await rejection(client([problemResponse(400, null)]).api.submitFiling({}));
    assert.match(nullBody.problem.detail, /HTTP 400/);
  });

  it('reports a network failure as a network ApiError', async () => {
    const error = await rejection(client([new TypeError('fetch failed')]).api.submitFiling({}));
    assert.equal(error.kind, ERROR_KINDS.network);
    assert.equal(error.status, null);
    assert.ok(error.cause instanceof TypeError);
  });

  it('reports an unreadable success body as an invalid response', async () => {
    const error = await rejection(client([textResponse(202, 'not json', 'application/json')]).api.submitFiling({}));
    assert.equal(error.kind, ERROR_KINDS.invalidResponse);
  });

  it('prefixes the base URL', async () => {
    const { api, calls } = client([jsonResponse(202, { filingId: ID, status: 'SUBMITTED' })], 'http://h:1');
    await api.submitFiling({});
    assert.equal(calls[0].url, 'http://h:1/api/filings');
  });
});

describe('getFiling', () => {
  it('returns the status body', async () => {
    const { api, calls } = client([jsonResponse(200, filing('ANALYZING'))]);
    assert.equal((await api.getFiling(ID)).status, 'ANALYZING');
    assert.equal(calls[0].url, `/api/filings/${ID}`);
  });

  it('encodes the id into the path', async () => {
    const { api, calls } = client([jsonResponse(200, filing('SUBMITTED'))]);
    await api.getFiling('../a b/?x');
    assert.equal(calls[0].url, '/api/filings/..%2Fa%20b%2F%3Fx');
  });

  it('throws on 404', async () => {
    const error = await rejection(client([problemResponse(404, { title: 'Not Found', status: 404 })]).api.getFiling(ID));
    assert.equal(error.status, 404);
  });
});

describe('getReport', () => {
  it('returns null while the report is not ready (404)', async () => {
    const { api, calls } = client([problemResponse(404, { title: 'Not Found', status: 404 })]);
    assert.equal(await api.getReport(ID), null);
    assert.equal(calls[0].url, `/api/reports/${ID}`);
  });

  it('returns null for a 404 with a non-JSON body', async () => {
    assert.equal(await client([textResponse(404, 'nope')]).api.getReport(ID), null);
  });

  it('returns the report on 200', async () => {
    const report = { filingId: ID, status: 'COMPLETED', findings: [] };
    assert.deepEqual(await client([jsonResponse(200, report)]).api.getReport(ID), report);
  });

  it('throws on a 500 ProblemDetail', async () => {
    const error = await rejection(client([problemResponse(500, { title: 'Error', status: 500 })]).api.getReport(ID));
    assert.equal(error.status, 500);
  });
});

describe('listFilings', () => {
  it('passes the limit as a query parameter', async () => {
    const { api, calls } = client([jsonResponse(200, [filing('COMPLETED')])]);
    assert.equal((await api.listFilings(5)).length, 1);
    assert.equal(calls[0].url, '/api/filings?limit=5');
  });

  it('throws on a 400 for an invalid limit', async () => {
    const error = await rejection(client([problemResponse(400, badRequest)]).api.listFilings(5));
    assert.equal(error.status, 400);
  });
});

describe('clampListLimit', () => {
  it('keeps the limit inside 1..100 and whole', () => {
    assert.equal(clampListLimit(0), 1);
    assert.equal(clampListLimit(-5), 1);
    assert.equal(clampListLimit(1), 1);
    assert.equal(clampListLimit(100), 100);
    assert.equal(clampListLimit(1000), 100);
    assert.equal(clampListLimit(2.7), 2);
  });

  it('uses the configured default for non-numbers', () => {
    assert.equal(clampListLimit(Number.NaN), 10);
    assert.equal(clampListLimit(undefined), 10);
    assert.equal(clampListLimit('7'), 10);
  });
});

describe('problemFromResponse', () => {
  it('detects JSON content types case-insensitively', async () => {
    const response = new Response('{"title":"T","detail":"D"}', {
      status: 409, headers: { 'Content-Type': 'Application/Problem+JSON; charset=utf-8' },
    });
    assert.deepEqual(await problemFromResponse(response), { title: 'T', detail: 'D', status: 409 });
  });

  it('uses a fallback title when the response has no status text', async () => {
    const problem = await problemFromResponse(new Response('', { status: 503 }));
    assert.equal(problem.title, 'Request failed');
    assert.equal(problem.status, 503);
  });
});
