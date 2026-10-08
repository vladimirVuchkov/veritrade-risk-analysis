import { CONFIG, SEVERITIES } from '../js/config.js';

export const MOCK_RULES = Object.freeze([
  { ruleId: 'FIN-001', category: 'FINANCIAL', severity: 'CRITICAL', pattern: /going concern/gi },
  { ruleId: 'FIN-002', category: 'FINANCIAL', severity: 'HIGH', pattern: /material weakness/gi },
  { ruleId: 'LEGAL-001', category: 'LEGAL', severity: 'HIGH', pattern: /pending litigation/gi },
  { ruleId: 'LEGAL-002', category: 'LEGAL', severity: 'MEDIUM', pattern: /class action/gi },
  { ruleId: 'OPS-001', category: 'OPERATIONAL', severity: 'MEDIUM', pattern: /supply chain disruption/gi },
  { ruleId: 'OPS-002', category: 'OPERATIONAL', severity: 'LOW', pattern: /key personnel/gi },
  { ruleId: 'CYBER-001', category: 'CYBERSECURITY', severity: 'HIGH', pattern: /cybersecurity incident/gi },
  { ruleId: 'CYBER-002', category: 'CYBERSECURITY', severity: 'HIGH', pattern: /data breach/gi },
  { ruleId: 'REG-001', category: 'REGULATORY', severity: 'HIGH', pattern: /SEC investigation/gi },
  { ruleId: 'REG-002', category: 'REGULATORY', severity: 'MEDIUM', pattern: /export control/gi },
  { ruleId: 'MKT-001', category: 'MARKET', severity: 'MEDIUM', pattern: /market volatility/gi },
  { ruleId: 'MKT-002', category: 'MARKET', severity: 'LOW', pattern: /interest rates?/gi },
]);

const severityRank = (severity) => SEVERITIES.indexOf(severity);

function excerptAround(content, position, length) {
  const context = CONFIG.ui.excerptContextChars;
  return content.slice(Math.max(0, position - context), position + length + context);
}

function findingsForRule(rule, content) {
  return [...content.matchAll(rule.pattern)].map((match) => ({
    category: rule.category,
    severity: rule.severity,
    ruleId: rule.ruleId,
    matchedText: match[0],
    excerpt: excerptAround(content, match.index, match[0].length),
    position: match.index,
  }));
}

function countBy(findings, key) {
  const counts = {};
  for (const finding of findings) counts[finding[key]] = (counts[finding[key]] || 0) + 1;
  return counts;
}

export function analyzeContent(content, rules = MOCK_RULES) {
  const findings = rules
    .flatMap((rule) => findingsForRule(rule, content))
    .sort((a, b) => severityRank(b.severity) - severityRank(a.severity) || a.position - b.position);
  const overallRiskLevel = findings.length === 0 ? 'NONE' : findings[0].severity;
  return {
    findings,
    summary: {
      totalFindings: findings.length,
      overallRiskLevel,
      byCategory: countBy(findings, 'category'),
      bySeverity: countBy(findings, 'severity'),
    },
  };
}
