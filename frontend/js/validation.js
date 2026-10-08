import { CONFIG } from './config.js';

const encoder = new TextEncoder();

export function utf8ByteLength(text) {
  return encoder.encode(text).length;
}

function isBlank(value) {
  return typeof value !== 'string' || value.trim().length === 0;
}

function validateLabelledText(value, label, maxLength) {
  if (isBlank(value)) {
    return `${label} is required.`;
  }
  if (value.trim().length > maxLength) {
    return `${label} must be at most ${maxLength} characters.`;
  }
  return null;
}

export function validateContent(content, maxBytes = CONFIG.limits.maxContentBytes) {
  if (isBlank(content)) {
    return 'Filing text is required.';
  }
  const bytes = utf8ByteLength(content);
  if (bytes > maxBytes) {
    return `Filing text is ${bytes} bytes; the limit is ${maxBytes} bytes (2 MB).`;
  }
  return null;
}

export function validateFiling({ companyName, title, content }) {
  const errors = {};
  const companyError = validateLabelledText(
    companyName,
    'Company name',
    CONFIG.limits.maxCompanyNameLength,
  );
  const titleError = validateLabelledText(title, 'Title', CONFIG.limits.maxTitleLength);
  const contentError = validateContent(content);
  if (companyError) errors.companyName = companyError;
  if (titleError) errors.title = titleError;
  if (contentError) errors.content = contentError;
  return { valid: Object.keys(errors).length === 0, errors };
}

export function validateTextFile({ name, size }, maxBytes = CONFIG.limits.maxContentBytes) {
  const extension = CONFIG.ui.acceptedFileExtension;
  if (typeof name !== 'string' || !name.toLowerCase().endsWith(extension)) {
    return `Only ${extension} files can be uploaded.`;
  }
  if (size > maxBytes) {
    return `The file is ${size} bytes; the limit is ${maxBytes} bytes (2 MB).`;
  }
  return null;
}

export function toSubmitRequest({ companyName, title, content }) {
  return { companyName: companyName.trim(), title: title.trim(), content };
}
