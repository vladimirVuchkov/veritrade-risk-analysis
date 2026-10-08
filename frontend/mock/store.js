import { randomUUID } from 'node:crypto';
import { MOCK_DEFAULTS } from './mock-config.js';
import { analyzeContent } from './rules.js';

function includesMarker(request, marker) {
  return request.title.includes(marker) || request.content.includes(marker);
}

export function statusAt(record, now, timings) {
  const elapsed = now - record.createdAt;
  if (elapsed < timings.submittedMs) return 'SUBMITTED';
  if (elapsed < timings.submittedMs + timings.analyzingMs) return 'ANALYZING';
  return record.willFail ? 'FAILED' : 'COMPLETED';
}

export function isReportReady(record, now, timings) {
  return now - record.createdAt >= timings.submittedMs + timings.analyzingMs + timings.reportDelayMs;
}

export function createStore({ timings = MOCK_DEFAULTS.timings, clock = Date.now } = {}) {
  const records = new Map();

  function toStatusResponse(record) {
    const status = statusAt(record, clock(), timings);
    return {
      filingId: record.filingId,
      companyName: record.companyName,
      title: record.title,
      status,
      submittedAt: new Date(record.createdAt).toISOString(),
      failureReason: status === 'FAILED' ? MOCK_DEFAULTS.failureReason : null,
    };
  }

  function toReport(record) {
    const generatedAt = new Date(
      record.createdAt + timings.submittedMs + timings.analyzingMs,
    ).toISOString();
    if (record.willFail) {
      return {
        filingId: record.filingId,
        status: 'FAILED',
        generatedAt,
        rulesVersion: null,
        failureReason: MOCK_DEFAULTS.failureReason,
        summary: null,
        findings: [],
      };
    }
    const { findings, summary } = analyzeContent(record.content);
    return {
      filingId: record.filingId,
      status: 'COMPLETED',
      generatedAt,
      rulesVersion: MOCK_DEFAULTS.rulesVersion,
      failureReason: null,
      summary,
      findings,
    };
  }

  return {
    add(request) {
      const record = {
        ...request,
        filingId: randomUUID(),
        createdAt: clock(),
        sequence: records.size,
        willFail: includesMarker(request, MOCK_DEFAULTS.markers.fail),
      };
      records.set(record.filingId, record);
      return toStatusResponse(record);
    },
    get(filingId) {
      const record = records.get(filingId);
      return record ? toStatusResponse(record) : null;
    },
    list(limit) {
      return [...records.values()]
        .sort((a, b) => b.sequence - a.sequence)
        .slice(0, limit)
        .map(toStatusResponse);
    },
    report(filingId) {
      const record = records.get(filingId);
      if (!record || !isReportReady(record, clock(), timings)) return null;
      return toReport(record);
    },
  };
}
