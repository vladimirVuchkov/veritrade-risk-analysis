import { createApiClient } from './api.js';
import { CONFIG } from './config.js';
import { describeContentSize, describeOutcome, describeStatus, problemMessage } from './format.js';
import { OUTCOMES, runAnalysisFlow } from './polling.js';
import { renderFilingsList, renderMessage, renderReport } from './render.js';
import { SAMPLE_FILING } from './sample.js';
import { toSubmitRequest, utf8ByteLength, validateFiling, validateTextFile } from './validation.js';

const doc = document;
const byId = (id) => doc.getElementById(id);
const api = createApiClient({ fetchFn: (url, init) => fetch(url, init) });

const dom = {
  form: byId('filing-form'),
  fields: {
    companyName: { input: byId('company-name'), error: byId('company-name-error') },
    title: { input: byId('filing-title'), error: byId('filing-title-error') },
    content: { input: byId('filing-content'), error: byId('filing-content-error') },
  },
  file: byId('filing-file'),
  contentSize: byId('filing-content-size'),
  sampleButton: byId('load-sample'),
  submitButton: byId('submit-button'),
  formMessage: byId('form-message'),
  progress: byId('progress'),
  report: byId('report'),
  filings: byId('filings'),
  refreshButton: byId('refresh-filings'),
};

let activeRun = 0;

function formValues() {
  const { companyName, title, content } = dom.fields;
  return { companyName: companyName.input.value, title: title.input.value, content: content.input.value };
}

function showFieldErrors(errors) {
  for (const [name, field] of Object.entries(dom.fields)) {
    field.error.textContent = errors[name] || '';
    field.input.setAttribute('aria-invalid', errors[name] ? 'true' : 'false');
  }
  const firstInvalid = Object.keys(dom.fields).find((name) => errors[name]);
  if (firstInvalid) dom.fields[firstInvalid].input.focus();
}

function updateContentSize() {
  dom.contentSize.textContent = describeContentSize(utf8ByteLength(dom.fields.content.input.value));
}

function showFormMessage(tone, message) {
  dom.formMessage.replaceChildren(renderMessage(doc, { tone, message }));
}

function showProgress(tone, message) {
  dom.progress.replaceChildren(renderMessage(doc, { tone, message }));
}

function loadSample() {
  dom.fields.companyName.input.value = SAMPLE_FILING.companyName;
  dom.fields.title.input.value = SAMPLE_FILING.title;
  dom.fields.content.input.value = SAMPLE_FILING.content;
  dom.file.value = '';
  showFieldErrors({});
  updateContentSize();
}

async function handleFileChange() {
  const file = dom.file.files[0];
  if (!file) return;
  const error = validateTextFile(file);
  if (error) {
    dom.file.value = '';
    showFieldErrors({ content: error });
    return;
  }
  try {
    dom.fields.content.input.value = await file.text();
    showFieldErrors({});
  } catch {
    showFieldErrors({ content: 'The file could not be read.' });
  }
  updateContentSize();
}

function showResult(result) {
  const { tone, message } = describeOutcome(result);
  showProgress(tone, message);
  if (result.outcome === OUTCOMES.completed) {
    dom.report.replaceChildren(renderReport(doc, result.report, result.filing));
    dom.report.focus();
  }
}

async function followFiling(filingId) {
  activeRun += 1;
  const run = activeRun;
  dom.report.replaceChildren();
  showProgress('info', 'Checking the filing status…');
  try {
    const result = await runAnalysisFlow({
      api,
      filingId,
      isCancelled: () => run !== activeRun,
      onStatus: (filing) => showProgress('info', describeStatus(filing.status)),
    });
    if (run !== activeRun) return;
    showResult(result);
  } catch (error) {
    if (run === activeRun) showProgress('error', problemMessage(error.problem));
  }
  refreshFilings();
}

async function handleSubmit(event) {
  event.preventDefault();
  dom.formMessage.replaceChildren();
  const values = formValues();
  const { valid, errors } = validateFiling(values);
  showFieldErrors(errors);
  if (!valid) return;
  dom.submitButton.disabled = true;
  try {
    const accepted = await api.submitFiling(toSubmitRequest(values));
    showFormMessage('success', 'Filing accepted for analysis.');
    followFiling(accepted.filingId);
  } catch (error) {
    showFormMessage('error', problemMessage(error.problem));
  } finally {
    dom.submitButton.disabled = false;
  }
}

async function refreshFilings() {
  try {
    const filings = await api.listFilings(CONFIG.ui.recentFilingsLimit);
    dom.filings.replaceChildren(renderFilingsList(doc, filings, (filing) => followFiling(filing.filingId)));
  } catch (error) {
    dom.filings.replaceChildren(renderMessage(doc, { tone: 'error', message: problemMessage(error.problem) }));
  }
}

dom.form.addEventListener('submit', handleSubmit);
dom.sampleButton.addEventListener('click', loadSample);
dom.file.addEventListener('change', handleFileChange);
dom.fields.content.input.addEventListener('input', updateContentSize);
dom.refreshButton.addEventListener('click', refreshFilings);
updateContentSize();
refreshFilings();
