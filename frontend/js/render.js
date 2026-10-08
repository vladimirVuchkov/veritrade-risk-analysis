import { SEVERITIES } from './config.js';
import {
  buildCategoryMatrix,
  formatDateTime,
  humanize,
  levelClass,
  statusClass,
} from './format.js';
import { splitExcerpt } from './highlight.js';

const TERMINAL_REPORT_FAILED = 'FAILED';

export function element(doc, tag, { className, text, attrs } = {}, children = []) {
  const node = doc.createElement(tag);
  if (className) node.className = className;
  for (const [name, value] of Object.entries(attrs || {})) node.setAttribute(name, String(value));
  if (text !== undefined && text !== null) node.appendChild(doc.createTextNode(String(text)));
  for (const child of children) node.appendChild(child);
  return node;
}

export function renderBadge(doc, value) {
  return element(doc, 'span', { className: `badge ${levelClass(value)}`, text: humanize(value) });
}

export function renderExcerpt(doc, finding) {
  const paragraph = element(doc, 'p', { className: 'excerpt' });
  for (const segment of splitExcerpt(finding.excerpt, finding.matchedText, finding.position)) {
    paragraph.appendChild(segment.highlighted
      ? element(doc, 'mark', { text: segment.text })
      : doc.createTextNode(segment.text));
  }
  return paragraph;
}

function headerRow(doc, labels) {
  const cells = labels.map((label) => element(doc, 'th', { text: label, attrs: { scope: 'col' } }));
  return element(doc, 'thead', {}, [element(doc, 'tr', {}, cells)]);
}

function table(doc, { caption, className, labels, rows }) {
  return element(doc, 'div', { className: 'table-scroll' }, [
    element(doc, 'table', { className }, [
      element(doc, 'caption', { text: caption }),
      headerRow(doc, labels),
      element(doc, 'tbody', {}, rows),
    ]),
  ]);
}

function matrixRow(doc, label, counts, total, isTotal) {
  const cells = SEVERITIES.map((severity) => element(doc, 'td', {
    className: counts[severity] > 0 ? `count ${levelClass(severity)}` : 'count zero',
    text: counts[severity],
  }));
  return element(doc, 'tr', { className: isTotal ? 'total-row' : undefined }, [
    element(doc, 'th', { text: label, attrs: { scope: 'row' } }),
    ...cells,
    element(doc, 'td', { className: 'count', text: total }),
  ]);
}

export function renderCategoryMatrix(doc, findings) {
  const matrix = buildCategoryMatrix(findings);
  const rows = matrix.rows.map((row) => matrixRow(doc, humanize(row.category), row.counts, row.total, false));
  rows.push(matrixRow(doc, 'Total', matrix.totals.counts, matrix.totals.total, true));
  return table(doc, {
    caption: 'Findings by category and severity',
    className: 'matrix',
    labels: ['Category', ...SEVERITIES.map(humanize), 'Total'],
    rows,
  });
}

function findingRow(doc, finding) {
  return element(doc, 'tr', {}, [
    element(doc, 'td', { attrs: { 'data-label': 'Severity' } }, [renderBadge(doc, finding.severity)]),
    element(doc, 'td', { text: humanize(finding.category), attrs: { 'data-label': 'Category' } }),
    element(doc, 'td', { className: 'rule-id', text: finding.ruleId, attrs: { 'data-label': 'Rule' } }),
    element(doc, 'td', { attrs: { 'data-label': 'Excerpt' } }, [renderExcerpt(doc, finding)]),
  ]);
}

export function renderFindingsTable(doc, findings) {
  if (!Array.isArray(findings) || findings.length === 0) {
    return element(doc, 'p', { className: 'empty', text: 'No risk findings were detected in this filing.' });
  }
  return table(doc, {
    caption: 'Findings (highest severity first)',
    className: 'findings stacked',
    labels: ['Severity', 'Category', 'Rule', 'Excerpt'],
    rows: findings.map((finding) => findingRow(doc, finding)),
  });
}

function definition(doc, term, valueNode) {
  return [element(doc, 'dt', { text: term }), element(doc, 'dd', {}, [valueNode])];
}

export function renderSummary(doc, report) {
  const findings = Array.isArray(report.findings) ? report.findings : [];
  const level = report.summary?.overallRiskLevel;
  const total = report.summary?.totalFindings ?? findings.length;
  return element(doc, 'dl', { className: 'summary' }, [
    ...definition(doc, 'Overall risk level', renderBadge(doc, level)),
    ...definition(doc, 'Findings', doc.createTextNode(String(total))),
    ...definition(doc, 'Generated', doc.createTextNode(formatDateTime(report.generatedAt))),
    ...definition(doc, 'Rules version', doc.createTextNode(report.rulesVersion || '—')),
  ]);
}

function renderFailedReport(doc, report) {
  return element(doc, 'div', { className: 'message tone-error', attrs: { role: 'alert' } }, [
    element(doc, 'strong', { text: 'Analysis failed. ' }),
    doc.createTextNode(report.failureReason || 'No reason was given.'),
  ]);
}

export function renderReport(doc, report, filing) {
  const children = [element(doc, 'h2', { text: 'Risk report', attrs: { id: 'report-heading' } })];
  if (filing) {
    children.push(element(doc, 'p', { className: 'report-subject' }, [
      element(doc, 'strong', { text: filing.companyName }),
      doc.createTextNode(` — ${filing.title}`),
    ]));
  }
  if (report.status === TERMINAL_REPORT_FAILED) {
    children.push(renderFailedReport(doc, report));
  } else {
    children.push(
      renderSummary(doc, report),
      renderCategoryMatrix(doc, report.findings),
      renderFindingsTable(doc, report.findings),
    );
  }
  return element(doc, 'article', { className: 'report', attrs: { 'aria-labelledby': 'report-heading' } }, children);
}

export function renderMessage(doc, { tone, message }) {
  const role = tone === 'error' ? 'alert' : 'status';
  return element(doc, 'div', { className: `message tone-${tone}`, text: message, attrs: { role } });
}

function filingRow(doc, filing, onSelect) {
  const button = element(doc, 'button', {
    className: 'link-button',
    text: 'Open',
    attrs: { type: 'button', 'aria-label': `Open filing: ${filing.title}` },
  });
  button.addEventListener('click', () => onSelect(filing));
  return element(doc, 'tr', {}, [
    element(doc, 'td', { text: filing.companyName, attrs: { 'data-label': 'Company' } }),
    element(doc, 'td', { text: filing.title, attrs: { 'data-label': 'Title' } }),
    element(doc, 'td', { attrs: { 'data-label': 'Status' } }, [
      element(doc, 'span', { className: `badge ${statusClass(filing.status)}`, text: humanize(filing.status) }),
    ]),
    element(doc, 'td', { text: formatDateTime(filing.submittedAt), attrs: { 'data-label': 'Submitted' } }),
    element(doc, 'td', { attrs: { 'data-label': 'Action' } }, [button]),
  ]);
}

export function renderFilingsList(doc, filings, onSelect) {
  if (!Array.isArray(filings) || filings.length === 0) {
    return element(doc, 'p', { className: 'empty', text: 'No filings have been submitted yet.' });
  }
  return table(doc, {
    caption: 'Most recent filings, newest first',
    className: 'filings stacked',
    labels: ['Company', 'Title', 'Status', 'Submitted', 'Action'],
    rows: filings.map((filing) => filingRow(doc, filing, onSelect)),
  });
}
