const BYTES_PER_MEBIBYTE = 1024 * 1024;

export const MOCK_DEFAULTS = Object.freeze({
  host: '127.0.0.1',
  port: 8090,
  timings: Object.freeze({
    submittedMs: 1500,
    analyzingMs: 3000,
    reportDelayMs: 2500,
  }),
  maxRequestBytes: 3 * BYTES_PER_MEBIBYTE,
  maxCorrelationIdLength: 128,
  defaultListLimit: 20,
  unavailableReportRequests: 0,
  rulesVersion: 'mock-1.0',
  markers: Object.freeze({
    fail: '[fail]',
    serverError: '[server-error]',
  }),
  failureReason: 'Analysis failed after 3 attempts: rule engine error (simulated by the mock)',
});
