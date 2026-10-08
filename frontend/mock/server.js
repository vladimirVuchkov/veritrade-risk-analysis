import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { extname, join, normalize, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { CONFIG } from '../js/config.js';
import { validateFiling } from '../js/validation.js';
import { MOCK_DEFAULTS } from './mock-config.js';
import { createStore } from './store.js';

const STATIC_ROOT = fileURLToPath(new URL('..', import.meta.url));
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MIME_TYPES = Object.freeze({
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.svg': 'image/svg+xml',
});
const STATUS = Object.freeze({
  ok: 200, accepted: 202, badRequest: 400, notFound: 404, methodNotAllowed: 405,
  payloadTooLarge: 413, internalError: 500, serviceUnavailable: 503,
});

class HttpProblem extends Error {
  constructor(status, title, detail) {
    super(detail);
    this.status = status;
    this.title = title;
    this.detail = detail;
  }
}

function sendJson(res, status, body, headers = {}) {
  res.writeHead(status, { 'Content-Type': 'application/json', ...headers });
  res.end(JSON.stringify(body));
}

function sendProblem(res, { status, title, detail }, instance) {
  res.writeHead(status, { 'Content-Type': 'application/problem+json' });
  res.end(JSON.stringify({ type: 'about:blank', title, status, detail, instance }));
}

function sendHtmlError(res, status, title) {
  res.writeHead(status, { 'Content-Type': 'text/html' });
  res.end(`<html><head><title>${status} ${title}</title></head><body><h1>${title}</h1></body></html>`);
}

function readBody(req, maxBytes) {
  return new Promise((resolveBody, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size <= maxBytes) chunks.push(chunk);
    });
    req.on('end', () => (size > maxBytes ? reject(new HttpProblem(STATUS.payloadTooLarge, 'Payload Too Large'))
      : resolveBody(Buffer.concat(chunks).toString('utf8'))));
    req.on('error', reject);
  });
}

function parseSubmitRequest(raw) {
  let body;
  try {
    body = JSON.parse(raw);
  } catch {
    throw new HttpProblem(STATUS.badRequest, 'Bad Request', 'The request body is not valid JSON.');
  }
  if (body === null || typeof body !== 'object' || Array.isArray(body)) {
    throw new HttpProblem(STATUS.badRequest, 'Bad Request', 'The request body must be a JSON object.');
  }
  const { errors } = validateFiling(body);
  const messages = Object.values(errors);
  if (messages.length > 0) throw new HttpProblem(STATUS.badRequest, 'Bad Request', messages.join(' '));
  return { companyName: body.companyName.trim(), title: body.title.trim(), content: body.content };
}

function parseLimit(searchParams) {
  if (!searchParams.has('limit')) return MOCK_DEFAULTS.defaultListLimit;
  const raw = searchParams.get('limit');
  const limit = Number(raw);
  const { minListLimit, maxListLimit } = CONFIG.limits;
  if (!/^\d+$/.test(raw) || limit < minListLimit || limit > maxListLimit) {
    throw new HttpProblem(STATUS.badRequest, 'Bad Request',
      `limit must be an integer between ${minListLimit} and ${maxListLimit}.`);
  }
  return limit;
}

function requireUuid(id) {
  if (!UUID_PATTERN.test(id)) {
    throw new HttpProblem(STATUS.badRequest, 'Bad Request', 'The filing id must be a UUID.');
  }
  return id.toLowerCase();
}

function notFound(detail) {
  return new HttpProblem(STATUS.notFound, 'Not Found', detail);
}

function correlationIdOf(req) {
  const received = req.headers[CONFIG.api.correlationHeader.toLowerCase()];
  const valid = typeof received === 'string' && received.length > 0
    && received.length <= MOCK_DEFAULTS.maxCorrelationIdLength;
  return valid ? received : randomUUID();
}

async function submitFiling(req, res, store) {
  const request = parseSubmitRequest(await readBody(req, MOCK_DEFAULTS.maxRequestBytes));
  if (request.title.includes(MOCK_DEFAULTS.markers.serverError)) {
    throw new HttpProblem(STATUS.internalError, 'Internal Server Error', 'Simulated server error.');
  }
  const filing = store.add(request);
  sendJson(res, STATUS.accepted, { filingId: filing.filingId, status: filing.status }, {
    Location: `${CONFIG.api.filingsPath}/${filing.filingId}`,
  });
}

function unavailableReports(count) {
  let remaining = count;
  return () => {
    if (remaining <= 0) return false;
    remaining -= 1;
    return true;
  };
}

function routeApi(req, url, store, takeUnavailable) {
  const { filingsPath, reportsPath } = CONFIG.api;
  const path = url.pathname.replace(/\/+$/, '');
  if (path === filingsPath) {
    if (req.method === 'POST') return (res) => submitFiling(req, res, store);
    if (req.method === 'GET') {
      return (res) => sendJson(res, STATUS.ok, store.list(parseLimit(url.searchParams)));
    }
    throw new HttpProblem(STATUS.methodNotAllowed, 'Method Not Allowed', `${req.method} is not supported.`);
  }
  const [, prefix, id] = path.match(/^(\/api\/filings|\/api\/reports)\/([^/]+)$/) || [];
  if (!prefix) throw notFound('No such API resource.');
  if (req.method !== 'GET') {
    throw new HttpProblem(STATUS.methodNotAllowed, 'Method Not Allowed', `${req.method} is not supported.`);
  }
  const filingId = requireUuid(id);
  if (prefix === filingsPath) {
    const filing = store.get(filingId);
    if (!filing) throw notFound(`Filing ${filingId} was not found.`);
    return (res) => sendJson(res, STATUS.ok, filing);
  }
  return (res) => {
    if (takeUnavailable()) {
      throw new HttpProblem(STATUS.serviceUnavailable, 'Service Unavailable', 'Reporting is restarting (simulated).');
    }
    const report = store.report(filingId);
    if (!report) throw notFound(`The report for filing ${filingId} is not available yet.`);
    sendJson(res, STATUS.ok, report);
  };
}

async function handleApi(req, res, url, store, takeUnavailable) {
  res.setHeader(CONFIG.api.correlationHeader, correlationIdOf(req));
  try {
    await routeApi(req, url, store, takeUnavailable)(res);
  } catch (error) {
    if (error.status === STATUS.payloadTooLarge) {
      sendHtmlError(res, error.status, error.title);
      return;
    }
    const problem = error instanceof HttpProblem ? error
      : { status: STATUS.internalError, title: 'Internal Server Error', detail: 'Unexpected mock error.' };
    sendProblem(res, problem, url.pathname);
  }
}

function safeDecode(pathname) {
  try {
    return decodeURIComponent(pathname);
  } catch {
    return null;
  }
}

export function resolveStaticPath(root, pathname) {
  const decoded = safeDecode(pathname);
  if (decoded === null || decoded.includes('\0')) return null;
  const relative = normalize(decoded).replace(/^([/\\])+/, '');
  const absoluteRoot = resolve(root);
  const target = resolve(absoluteRoot, relative || 'index.html');
  return target === absoluteRoot || target.startsWith(absoluteRoot + sep) ? target : null;
}

async function serveStatic(req, res, url, root) {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    sendHtmlError(res, STATUS.methodNotAllowed, 'Method Not Allowed');
    return;
  }
  const target = resolveStaticPath(root, url.pathname);
  if (!target) {
    sendHtmlError(res, STATUS.notFound, 'Not Found');
    return;
  }
  const file = extname(target) ? target : join(target, 'index.html');
  try {
    const body = await readFile(file);
    const type = MIME_TYPES[extname(file)] || MIME_TYPES['.html'];
    res.writeHead(STATUS.ok, {
      'Content-Type': type,
      'Content-Security-Policy': MOCK_DEFAULTS.contentSecurityPolicy,
    });
    res.end(req.method === 'HEAD' ? undefined : body);
  } catch {
    sendHtmlError(res, STATUS.notFound, 'Not Found');
  }
}

export function createMockServer({
  timings = MOCK_DEFAULTS.timings,
  clock,
  staticRoot = STATIC_ROOT,
  unavailableReportRequests = MOCK_DEFAULTS.unavailableReportRequests,
} = {}) {
  const store = createStore({ timings, clock });
  const takeUnavailable = unavailableReports(unavailableReportRequests);
  return createServer((req, res) => {
    const url = new URL(req.url, 'http://mock.local');
    if (url.pathname.startsWith('/api/')) {
      handleApi(req, res, url, store, takeUnavailable);
    } else {
      serveStatic(req, res, url, staticRoot);
    }
  });
}

function numberArg(argv, flag, fallback) {
  const index = argv.indexOf(flag);
  return index >= 0 && argv[index + 1] ? Number(argv[index + 1]) : fallback;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = numberArg(process.argv, '--port', Number(process.env.PORT) || MOCK_DEFAULTS.port);
  const unavailableReportRequests = numberArg(
    process.argv, '--unavailable-reports', MOCK_DEFAULTS.unavailableReportRequests,
  );
  createMockServer({ unavailableReportRequests }).listen(port, MOCK_DEFAULTS.host, () => {
    process.stdout.write(`Mock VeriTrade API and UI on http://${MOCK_DEFAULTS.host}:${port}/\n`);
  });
}
