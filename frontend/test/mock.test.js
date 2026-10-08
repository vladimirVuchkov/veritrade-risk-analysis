import assert from 'node:assert/strict';
import { join, resolve, sep } from 'node:path';
import { describe, it } from 'node:test';
import { CATEGORIES, CONFIG, RISK_LEVELS } from '../js/config.js';
import { SAMPLE_FILING } from '../js/sample.js';
import { validateFiling } from '../js/validation.js';
import { MOCK_DEFAULTS } from '../mock/mock-config.js';
import { analyzeContent } from '../mock/rules.js';
import { resolveStaticPath } from '../mock/server.js';
import { createStore, isReportReady, statusAt } from '../mock/store.js';

const TIMINGS = { submittedMs: 100, analyzingMs: 200, reportDelayMs: 50 };
const request = { companyName: 'Acme', title: '10-K', content: 'We face pending litigation.' };

function fakeClock(start = 1_000_000) {
  let now = start;
  return { clock: () => now, advance: (ms) => { now += ms; } };
}

describe('mock timeline', () => {
  const record = { createdAt: 0, willFail: false };

  it('moves SUBMITTED -> ANALYZING -> COMPLETED at the configured boundaries', () => {
    assert.equal(statusAt(record, 0, TIMINGS), 'SUBMITTED');
    assert.equal(statusAt(record, 99, TIMINGS), 'SUBMITTED');
    assert.equal(statusAt(record, 100, TIMINGS), 'ANALYZING');
    assert.equal(statusAt(record, 299, TIMINGS), 'ANALYZING');
    assert.equal(statusAt(record, 300, TIMINGS), 'COMPLETED');
  });

  it('ends in FAILED for failing records', () => {
    assert.equal(statusAt({ ...record, willFail: true }, 300, TIMINGS), 'FAILED');
  });

  it('makes the report available only after the report delay', () => {
    assert.equal(isReportReady(record, 349, TIMINGS), false);
    assert.equal(isReportReady(record, 350, TIMINGS), true);
  });
});

describe('mock store', () => {
  it('serves status, list and report according to the clock', () => {
    const { clock, advance } = fakeClock();
    const store = createStore({ timings: TIMINGS, clock });
    const created = store.add(request);
    assert.equal(created.status, 'SUBMITTED');
    assert.equal(store.report(created.filingId), null);
    advance(300);
    assert.equal(store.get(created.filingId).status, 'COMPLETED');
    assert.equal(store.report(created.filingId), null);
    advance(50);
    const report = store.report(created.filingId);
    assert.equal(report.status, 'COMPLETED');
    assert.equal(report.summary.overallRiskLevel, 'HIGH');
    assert.equal(report.rulesVersion, MOCK_DEFAULTS.rulesVersion);
  });

  it('fails filings that carry the fail marker', () => {
    const { clock, advance } = fakeClock();
    const store = createStore({ timings: TIMINGS, clock });
    const created = store.add({ ...request, title: `Bad ${MOCK_DEFAULTS.markers.fail}` });
    advance(350);
    const status = store.get(created.filingId);
    assert.equal(status.status, 'FAILED');
    assert.equal(status.failureReason, MOCK_DEFAULTS.failureReason);
    const report = store.report(created.filingId);
    assert.deepEqual([report.status, report.summary, report.findings], ['FAILED', null, []]);
  });

  it('lists the newest filings first and honours the limit', () => {
    const { clock } = fakeClock();
    const store = createStore({ timings: TIMINGS, clock });
    const ids = ['a', 'b', 'c'].map((title) => store.add({ ...request, title }).filingId);
    assert.deepEqual(store.list(2).map((item) => item.filingId), [ids[2], ids[1]]);
    assert.equal(store.list(100).length, 3);
  });

  it('returns null for unknown filings', () => {
    const store = createStore({ timings: TIMINGS });
    assert.equal(store.get('missing'), null);
    assert.equal(store.report('missing'), null);
  });
});

describe('mock rules', () => {
  it('returns NONE and no findings for clean text', () => {
    const result = analyzeContent('Revenue grew steadily.');
    assert.deepEqual(result.findings, []);
    assert.deepEqual(result.summary, { totalFindings: 0, overallRiskLevel: 'NONE', byCategory: {}, bySeverity: {} });
  });

  it('orders findings by severity, then position, and builds excerpts like the backend', () => {
    const content = `${'x'.repeat(200)} key personnel and going concern and pending litigation`;
    const { findings, summary } = analyzeContent(content);
    assert.deepEqual(findings.map((item) => item.severity), ['CRITICAL', 'HIGH', 'LOW']);
    for (const item of findings) {
      assert.equal(content.slice(item.position, item.position + item.matchedText.length), item.matchedText);
      const offset = Math.min(item.position, CONFIG.ui.excerptContextChars);
      assert.equal(item.excerpt.slice(offset, offset + item.matchedText.length), item.matchedText);
    }
    assert.equal(summary.overallRiskLevel, 'CRITICAL');
    assert.deepEqual(summary.bySeverity, { CRITICAL: 1, HIGH: 1, LOW: 1 });
  });

  it('finds every category in the embedded sample, which is itself valid', () => {
    assert.equal(validateFiling(SAMPLE_FILING).valid, true);
    const { findings, summary } = analyzeContent(SAMPLE_FILING.content);
    assert.deepEqual(new Set(findings.map((item) => item.category)), new Set(CATEGORIES));
    assert.ok(RISK_LEVELS.includes(summary.overallRiskLevel));
  });
});

describe('resolveStaticPath', () => {
  const root = resolve('/srv/site');

  it('maps the root and nested files inside the static root', () => {
    assert.equal(resolveStaticPath(root, '/'), join(root, 'index.html'));
    assert.equal(resolveStaticPath(root, '/js/app.js'), join(root, 'js', 'app.js'));
  });

  it('keeps traversal attempts inside the static root', () => {
    for (const path of ['/../etc/passwd', '/%2e%2e/%2e%2e/etc/passwd', '/js/../../x', '/..%2f..%2fx']) {
      const target = resolveStaticPath(root, path);
      assert.ok(target === null || target.startsWith(root + sep), `${path} -> ${target}`);
    }
  });

  it('rejects malformed encodings and NUL bytes', () => {
    for (const path of ['/%E0%A4%A', '/%', '/a%00b']) {
      assert.equal(resolveStaticPath(root, path), null, path);
    }
  });
});
