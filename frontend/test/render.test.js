import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { CATEGORIES, RISK_LEVELS, SEVERITIES } from '../js/config.js';
import {
  renderBadge,
  renderCategoryMatrix,
  renderExcerpt,
  renderFilingsList,
  renderFindingsTable,
  renderMessage,
  renderReport,
} from '../js/render.js';
import { byClass, byTag, createFakeDocument, serialize } from './support/fake-dom.js';
import { filing } from './support/fakes.js';

const doc = createFakeDocument();
const ID = '3f2b8c1e-6a4d-4e2f-9b7a-1c5d8e9f0a12';
const XSS = [
  '<script>alert(1)</script>',
  '<img src=x onerror=alert(2)>',
  '"><svg onload=alert(3)>',
  '&lt;b&gt;already escaped&lt;/b&gt;',
];

function finding(overrides = {}) {
  return {
    category: 'LEGAL',
    severity: 'HIGH',
    ruleId: 'LEGAL-001',
    matchedText: 'pending litigation',
    excerpt: 'We are subject to pending litigation today.',
    position: 18,
    ...overrides,
  };
}

function report(findings, overallRiskLevel) {
  return {
    filingId: ID,
    status: 'COMPLETED',
    generatedAt: '2026-10-07T12:00:02Z',
    rulesVersion: '1.0',
    failureReason: null,
    summary: { totalFindings: findings.length, overallRiskLevel, byCategory: {}, bySeverity: {} },
    findings,
  };
}

function assertNoActiveMarkup(node) {
  const dangerous = ['SCRIPT', 'IMG', 'SVG', 'IFRAME', 'B'];
  for (const tag of dangerous) assert.equal(byTag(node, tag).length, 0, `unexpected <${tag}>`);
  for (const element of byTag(node, 'td').concat(byTag(node, 'p'), byTag(node, 'span'))) {
    for (const name of element.attributes.keys()) assert.doesNotMatch(name, /^on/i);
  }
}

describe('renderReport', () => {
  it('renders a NONE report with zero findings', () => {
    const view = renderReport(doc, report([], 'NONE'), filing('COMPLETED'));
    const badge = byClass(view, 'badge')[0];
    assert.equal(badge.textContent, 'None');
    assert.match(badge.className, /level-none/);
    assert.equal(byClass(view, 'empty')[0].textContent, 'No risk findings were detected in this filing.');
    assert.equal(byTag(view, 'mark').length, 0);
    assert.equal(byClass(view, 'total-row')[0].textContent, 'Total00000');
    assert.equal(byClass(view, 'findings').length, 0);
  });

  it('renders the overall level badge for every risk level', () => {
    for (const level of RISK_LEVELS) {
      const view = renderReport(doc, report([], level));
      const badge = byClass(view, 'badge')[0];
      assert.match(badge.className, new RegExp(`level-${level.toLowerCase()}`));
    }
  });

  it('renders an unknown level safely', () => {
    const view = renderReport(doc, { ...report([], 'NONE'), summary: null });
    assert.equal(byClass(view, 'badge')[0].className, 'badge level-unknown');
  });

  it('renders a finding row with a badge for every severity', () => {
    const findings = SEVERITIES.map((severity) => finding({ severity }));
    const view = renderReport(doc, report(findings, 'CRITICAL'));
    const rows = byTag(byClass(view, 'findings')[0], 'tbody')[0].childNodes;
    assert.equal(rows.length, SEVERITIES.length);
    rows.forEach((row, index) => {
      const badge = byClass(row, 'badge')[0];
      assert.match(badge.className, new RegExp(`level-${SEVERITIES[index].toLowerCase()}`));
      assert.equal(badge.textContent, SEVERITIES[index].charAt(0) + SEVERITIES[index].slice(1).toLowerCase());
    });
  });

  it('renders the category by severity matrix', () => {
    const findings = [
      finding({ category: 'LEGAL', severity: 'HIGH' }),
      finding({ category: 'CYBERSECURITY', severity: 'CRITICAL' }),
      finding({ category: 'CYBERSECURITY', severity: 'CRITICAL' }),
    ];
    const matrix = byClass(renderReport(doc, report(findings, 'CRITICAL')), 'matrix')[0];
    const bodyRows = byTag(matrix, 'tbody')[0].childNodes;
    assert.equal(bodyRows.length, CATEGORIES.length + 1);
    const cyber = bodyRows.find((row) => row.childNodes[0].textContent === 'Cybersecurity');
    assert.deepEqual(cyber.childNodes.map((cell) => cell.textContent), ['Cybersecurity', '0', '0', '0', '2', '2']);
    assert.equal(bodyRows.at(-1).textContent, 'Total00123');
  });

  it('shows the summary details and subject', () => {
    const view = renderReport(doc, report([finding()], 'HIGH'), filing('COMPLETED'));
    const summary = byClass(view, 'summary')[0];
    assert.match(summary.textContent, /Findings1/);
    assert.match(summary.textContent, /2026-10-07 12:00:02 UTC/);
    assert.match(summary.textContent, /Rules version1\.0/);
    assert.equal(byClass(view, 'report-subject')[0].textContent, 'Acme Holdings Inc. — Form 10-K');
  });

  it('renders a FAILED report with its reason and no tables', () => {
    const failed = {
      filingId: ID, status: 'FAILED', generatedAt: '2026-10-07T12:00:05Z', failureReason: 'rule engine error',
      summary: null, findings: [],
    };
    const view = renderReport(doc, failed);
    const alert = byClass(view, 'tone-error')[0];
    assert.equal(alert.getAttribute('role'), 'alert');
    assert.equal(alert.textContent, 'Analysis failed. rule engine error');
    assert.equal(byTag(view, 'table').length, 0);
  });

  it('renders a FAILED report without a reason', () => {
    const view = renderReport(doc, { status: 'FAILED', findings: [] });
    assert.match(view.textContent, /No reason was given/);
  });
});

describe('renderExcerpt', () => {
  it('wraps exactly the matched text in a mark element', () => {
    const paragraph = renderExcerpt(doc, finding());
    assert.equal(paragraph.textContent, 'We are subject to pending litigation today.');
    const marks = byTag(paragraph, 'mark');
    assert.equal(marks.length, 1);
    assert.equal(marks[0].textContent, 'pending litigation');
  });

  it('renders the excerpt without a mark when the match is absent', () => {
    const paragraph = renderExcerpt(doc, finding({ matchedText: 'not there' }));
    assert.equal(byTag(paragraph, 'mark').length, 0);
    assert.equal(paragraph.textContent, 'We are subject to pending litigation today.');
  });

  it('renders an empty paragraph for a missing excerpt', () => {
    assert.equal(renderExcerpt(doc, finding({ excerpt: null })).textContent, '');
  });
});

describe('XSS safety', () => {
  for (const payload of XSS) {
    it(`renders ${payload} as inert text everywhere`, () => {
      const hostile = finding({
        excerpt: `${payload} pending litigation ${payload}`,
        matchedText: 'pending litigation',
        position: payload.length + 1,
        ruleId: payload,
        category: payload,
        severity: payload,
      });
      const view = renderReport(doc, report([hostile], payload), filing('COMPLETED', {
        companyName: payload, title: payload,
      }));
      assertNoActiveMarkup(view);
      assert.ok(view.textContent.includes(payload));
      assert.ok(!serialize(view).includes(payload));
    });
  }

  it('highlights a match that itself contains markup as text', () => {
    const paragraph = renderExcerpt(doc, finding({
      excerpt: 'x <script>alert(1)</script> y', matchedText: '<script>alert(1)</script>', position: 2,
    }));
    const mark = byTag(paragraph, 'mark')[0];
    assert.equal(mark.textContent, '<script>alert(1)</script>');
    assert.equal(serialize(mark), '<mark>&lt;script&gt;alert(1)&lt;/script&gt;</mark>');
  });

  it('renders a hostile failure reason as text', () => {
    const view = renderReport(doc, { status: 'FAILED', failureReason: XSS[0], findings: [] });
    assertNoActiveMarkup(view);
    assert.ok(serialize(view).includes('&lt;script&gt;'));
  });

  it('renders hostile filings in the list as text', () => {
    const view = renderFilingsList(doc, [filing(XSS[1], { companyName: XSS[0], title: XSS[2] })], () => {});
    assertNoActiveMarkup(view);
    assert.equal(byClass(view, 'badge')[0].className, 'badge status-unknown');
    assert.equal(byTag(view, 'button')[0].getAttribute('aria-label'), `Open filing: ${XSS[2]}`);
  });

  it('fails loudly if any builder used innerHTML', () => {
    assert.throws(() => {
      doc.createElement('div').innerHTML = '<b>x</b>';
    }, /must not be used/);
  });
});

describe('renderFilingsList', () => {
  it('shows an empty state', () => {
    for (const filings of [[], null, undefined]) {
      assert.equal(renderFilingsList(doc, filings, () => {}).textContent, 'No filings have been submitted yet.');
    }
  });

  it('renders one row per filing and opens the selected one', () => {
    const selected = [];
    const filings = [filing('COMPLETED'), filing('FAILED', { filingId: 'other' })];
    const view = renderFilingsList(doc, filings, (value) => selected.push(value.filingId));
    const rows = byTag(byTag(view, 'tbody')[0], 'tr');
    assert.equal(rows.length, 2);
    assert.deepEqual(rows[0].childNodes.map((cell) => cell.getAttribute('data-label')), [
      'Company', 'Title', 'Status', 'Submitted', 'Action',
    ]);
    byTag(rows[1], 'button')[0].dispatch('click');
    assert.deepEqual(selected, ['other']);
    assert.equal(byTag(rows[1], 'button')[0].getAttribute('type'), 'button');
  });

  it('labels each status', () => {
    const view = renderFilingsList(doc, ['SUBMITTED', 'ANALYZING', 'COMPLETED', 'FAILED'].map((s) => filing(s)), () => {});
    assert.deepEqual(byClass(view, 'badge').map((badge) => badge.className), [
      'badge status-submitted', 'badge status-analyzing', 'badge status-completed', 'badge status-failed',
    ]);
  });
});

describe('small builders', () => {
  it('renders messages with the right live-region role', () => {
    assert.equal(renderMessage(doc, { tone: 'error', message: 'x' }).getAttribute('role'), 'alert');
    for (const tone of ['info', 'success', 'warning']) {
      assert.equal(renderMessage(doc, { tone, message: 'x' }).getAttribute('role'), 'status');
    }
  });

  it('renders badges, findings tables and matrices on their own', () => {
    assert.equal(renderBadge(doc, 'CRITICAL').textContent, 'Critical');
    assert.equal(byTag(renderFindingsTable(doc, [finding()]), 'caption')[0].textContent, 'Findings (highest severity first)');
    assert.equal(byTag(renderCategoryMatrix(doc, []), 'th')[0].getAttribute('scope'), 'col');
  });
});
