import { CATEGORIES, CONFIG, FILING_STATUSES, RISK_LEVELS, SEVERITIES } from './config.js';
import { OUTCOMES, PHASES } from './polling.js';

const UNKNOWN = 'unknown';
const BYTES_PER_KIB = 1024;
const BYTES_PER_MIB = BYTES_PER_KIB * BYTES_PER_KIB;
const ISO_DATE_TIME_LENGTH = 19;
const MS_PER_SECOND = 1000;

export function humanize(value) {
  if (typeof value !== 'string' || value.length === 0) return 'Unknown';
  const lower = value.toLowerCase().replace(/_/g, ' ');
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

export function levelClass(value) {
  const known = RISK_LEVELS.includes(value) || SEVERITIES.includes(value);
  return `level-${known ? value.toLowerCase() : UNKNOWN}`;
}

export function statusClass(status) {
  return `status-${FILING_STATUSES.includes(status) ? status.toLowerCase() : UNKNOWN}`;
}

export function formatDateTime(iso) {
  const date = new Date(iso);
  if (typeof iso !== 'string' || Number.isNaN(date.getTime())) return '—';
  return `${date.toISOString().replace('T', ' ').slice(0, ISO_DATE_TIME_LENGTH)} UTC`;
}

export function formatBytes(bytes) {
  if (bytes >= BYTES_PER_MIB) return `${(bytes / BYTES_PER_MIB).toFixed(2)} MB`;
  if (bytes >= BYTES_PER_KIB) return `${(bytes / BYTES_PER_KIB).toFixed(1)} KB`;
  return `${bytes} B`;
}

export function describeContentSize(bytes) {
  return `${formatBytes(bytes)} of ${formatBytes(CONFIG.limits.maxContentBytes)}`;
}

function emptySeverityCounts() {
  return Object.fromEntries(SEVERITIES.map((severity) => [severity, 0]));
}

export function buildCategoryMatrix(findings) {
  const rows = new Map(CATEGORIES.map((category) => [category, emptySeverityCounts()]));
  const totals = emptySeverityCounts();
  for (const finding of Array.isArray(findings) ? findings : []) {
    const counts = rows.get(finding?.category);
    if (!counts || !Object.hasOwn(counts, finding?.severity)) continue;
    counts[finding.severity] += 1;
    totals[finding.severity] += 1;
  }
  const sum = (counts) => SEVERITIES.reduce((total, severity) => total + counts[severity], 0);
  return {
    rows: CATEGORIES.map((category) => {
      const counts = rows.get(category);
      return { category, counts, total: sum(counts) };
    }),
    totals: { counts: totals, total: sum(totals) },
  };
}

export function problemMessage(problem) {
  if (!problem) return 'An unexpected error occurred.';
  const title = problem.title || 'Error';
  return problem.detail ? `${title}: ${problem.detail}` : title;
}

function timeoutMessage(phase) {
  const seconds = (CONFIG.polling.intervalMs / MS_PER_SECOND) * (phase === PHASES.report
    ? CONFIG.polling.reportMaxAttempts
    : CONFIG.polling.statusMaxAttempts);
  const what = phase === PHASES.report ? 'The report' : 'The analysis';
  return `${what} did not finish within about ${seconds} seconds. `
    + 'It may still complete; select the filing in "Recent filings" to check again.';
}

export function describeOutcome(result) {
  switch (result.outcome) {
    case OUTCOMES.completed:
      return { tone: 'success', message: 'Analysis completed.' };
    case OUTCOMES.failed:
      return { tone: 'error', message: `Analysis failed: ${result.reason || 'no reason was given.'}` };
    case OUTCOMES.timeout:
      return { tone: 'warning', message: timeoutMessage(result.phase) };
    default:
      return { tone: 'info', message: 'Stopped waiting for this filing.' };
  }
}

export function describeStatus(status) {
  const messages = {
    SUBMITTED: 'Submitted; waiting for the analysis to start…',
    ANALYZING: 'Analyzing the filing…',
    COMPLETED: 'Analysis completed; loading the report…',
    FAILED: 'Analysis failed.',
  };
  return messages[status] || `Status: ${humanize(status)}`;
}
