import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { CATEGORIES, FILING_STATUSES, RISK_LEVELS, SEVERITIES } from '../js/config.js';
import {
  buildCategoryMatrix,
  describeContentSize,
  describeOutcome,
  describeStatus,
  formatBytes,
  formatDateTime,
  humanize,
  levelClass,
  problemMessage,
  statusClass,
} from '../js/format.js';
import { OUTCOMES, PHASES } from '../js/polling.js';

describe('humanize', () => {
  it('turns enum values into readable labels', () => {
    assert.deepEqual(RISK_LEVELS.map(humanize), ['None', 'Low', 'Medium', 'High', 'Critical']);
    assert.deepEqual(CATEGORIES.map(humanize), [
      'Financial', 'Legal', 'Operational', 'Cybersecurity', 'Regulatory', 'Market',
    ]);
    assert.equal(humanize('SOME_VALUE'), 'Some value');
  });

  it('labels missing values as Unknown', () => {
    for (const value of [undefined, null, '', 3]) assert.equal(humanize(value), 'Unknown');
  });
});

describe('levelClass and statusClass', () => {
  it('maps every risk level and severity to its own class', () => {
    for (const value of [...RISK_LEVELS, ...SEVERITIES]) {
      assert.equal(levelClass(value), `level-${value.toLowerCase()}`);
    }
  });

  it('maps every filing status to its own class', () => {
    for (const status of FILING_STATUSES) assert.equal(statusClass(status), `status-${status.toLowerCase()}`);
  });

  it('never builds a class from an unknown value', () => {
    for (const value of ['"><script>', 'high', 'EXTREME', undefined, null, {}]) {
      assert.equal(levelClass(value), 'level-unknown');
      assert.equal(statusClass(value), 'status-unknown');
    }
  });
});

describe('formatDateTime', () => {
  it('formats ISO timestamps in UTC', () => {
    assert.equal(formatDateTime('2026-10-07T12:00:02Z'), '2026-10-07 12:00:02 UTC');
    assert.equal(formatDateTime('2026-10-07T14:00:02.123+02:00'), '2026-10-07 12:00:02 UTC');
  });

  it('shows a dash for missing or invalid values', () => {
    for (const value of [undefined, null, '', 'yesterday', 0]) assert.equal(formatDateTime(value), '—');
  });
});

describe('formatBytes', () => {
  it('chooses the unit at the boundaries', () => {
    assert.equal(formatBytes(0), '0 B');
    assert.equal(formatBytes(1023), '1023 B');
    assert.equal(formatBytes(1024), '1.0 KB');
    assert.equal(formatBytes(1024 * 1024 - 1), '1024.0 KB');
    assert.equal(formatBytes(1024 * 1024), '1.00 MB');
    assert.equal(formatBytes(2 * 1024 * 1024), '2.00 MB');
  });

  it('describes the size against the limit', () => {
    assert.equal(describeContentSize(10), '10 B of 2.00 MB');
  });
});

describe('buildCategoryMatrix', () => {
  it('lists all six categories with zero counts when there are no findings', () => {
    const matrix = buildCategoryMatrix([]);
    assert.deepEqual(matrix.rows.map((row) => row.category), CATEGORIES);
    for (const row of matrix.rows) {
      assert.equal(row.total, 0);
      assert.deepEqual(Object.keys(row.counts), SEVERITIES);
    }
    assert.equal(matrix.totals.total, 0);
  });

  it('counts findings by category and severity', () => {
    const matrix = buildCategoryMatrix([
      { category: 'LEGAL', severity: 'HIGH' },
      { category: 'LEGAL', severity: 'HIGH' },
      { category: 'LEGAL', severity: 'LOW' },
      { category: 'MARKET', severity: 'CRITICAL' },
    ]);
    const legal = matrix.rows.find((row) => row.category === 'LEGAL');
    assert.deepEqual(legal.counts, { LOW: 1, MEDIUM: 0, HIGH: 2, CRITICAL: 0 });
    assert.equal(legal.total, 3);
    assert.deepEqual(matrix.totals.counts, { LOW: 1, MEDIUM: 0, HIGH: 2, CRITICAL: 1 });
    assert.equal(matrix.totals.total, 4);
  });

  it('ignores unknown categories, severities and prototype keys', () => {
    const matrix = buildCategoryMatrix([
      { category: 'WEATHER', severity: 'HIGH' },
      { category: 'LEGAL', severity: 'EXTREME' },
      { category: 'LEGAL', severity: 'toString' },
      { category: '__proto__', severity: '__proto__' },
      null,
    ]);
    assert.equal(matrix.totals.total, 0);
  });

  it('treats a missing findings array as empty', () => {
    assert.equal(buildCategoryMatrix(undefined).totals.total, 0);
    assert.equal(buildCategoryMatrix(null).rows.length, CATEGORIES.length);
  });
});

describe('problemMessage', () => {
  it('combines title and detail', () => {
    assert.equal(problemMessage({ title: 'Bad Request', detail: 'content is blank' }), 'Bad Request: content is blank');
  });

  it('falls back sensibly', () => {
    assert.equal(problemMessage({ title: 'Not Found' }), 'Not Found');
    assert.equal(problemMessage({ detail: 'only detail' }), 'Error: only detail');
    assert.equal(problemMessage(undefined), 'An unexpected error occurred.');
  });
});

describe('describeOutcome', () => {
  it('describes completion', () => {
    assert.deepEqual(describeOutcome({ outcome: OUTCOMES.completed }), { tone: 'success', message: 'Analysis completed.' });
  });

  it('shows the failure reason', () => {
    const description = describeOutcome({ outcome: OUTCOMES.failed, reason: 'rule engine error' });
    assert.deepEqual(description, { tone: 'error', message: 'Analysis failed: rule engine error' });
    assert.match(describeOutcome({ outcome: OUTCOMES.failed, reason: null }).message, /no reason was given/);
  });

  it('gives a clear timeout message per phase', () => {
    const status = describeOutcome({ outcome: OUTCOMES.timeout, phase: PHASES.status });
    assert.equal(status.tone, 'warning');
    assert.match(status.message, /^The analysis did not finish within about 60 seconds/);
    assert.match(status.message, /Recent filings/);
    const report = describeOutcome({ outcome: OUTCOMES.timeout, phase: PHASES.report });
    assert.match(report.message, /^The report did not finish within about 30 seconds/);
  });

  it('describes cancellation', () => {
    assert.equal(describeOutcome({ outcome: OUTCOMES.cancelled }).tone, 'info');
  });
});

describe('describeStatus', () => {
  it('describes every filing status', () => {
    for (const status of FILING_STATUSES) assert.doesNotMatch(describeStatus(status), /^Status:/);
  });

  it('describes unknown statuses generically', () => {
    assert.equal(describeStatus('PAUSED'), 'Status: Paused');
  });
});
