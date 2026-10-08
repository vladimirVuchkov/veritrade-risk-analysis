const BYTES_PER_MEBIBYTE = 1024 * 1024;

export const CONFIG = Object.freeze({
  api: Object.freeze({
    filingsPath: '/api/filings',
    reportsPath: '/api/reports',
    correlationHeader: 'X-Correlation-Id',
  }),
  polling: Object.freeze({
    intervalMs: 2000,
    statusMaxAttempts: 30,
    reportMaxAttempts: 15,
    maxConsecutiveTransientErrors: 3,
    transientHttpStatuses: Object.freeze([502, 503, 504]),
  }),
  limits: Object.freeze({
    maxContentBytes: 2 * BYTES_PER_MEBIBYTE,
    maxCompanyNameLength: 200,
    maxTitleLength: 300,
    minListLimit: 1,
    maxListLimit: 100,
  }),
  ui: Object.freeze({
    recentFilingsLimit: 10,
    excerptContextChars: 120,
    acceptedFileExtension: '.txt',
  }),
});

export const RISK_LEVELS = Object.freeze(['NONE', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL']);
export const SEVERITIES = Object.freeze(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']);
export const CATEGORIES = Object.freeze([
  'FINANCIAL',
  'LEGAL',
  'OPERATIONAL',
  'CYBERSECURITY',
  'REGULATORY',
  'MARKET',
]);
export const FILING_STATUSES = Object.freeze(['SUBMITTED', 'ANALYZING', 'COMPLETED', 'FAILED']);
export const TERMINAL_STATUSES = Object.freeze(['COMPLETED', 'FAILED']);
