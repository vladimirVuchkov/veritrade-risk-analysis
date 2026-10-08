import { CONFIG } from './config.js';

function matchesAt(excerpt, matchedText, index) {
  return index >= 0 && excerpt.startsWith(matchedText, index);
}

function caseInsensitiveIndex(excerpt, matchedText) {
  const lowerExcerpt = excerpt.toLowerCase();
  const lowerMatch = matchedText.toLowerCase();
  if (lowerExcerpt.length !== excerpt.length || lowerMatch.length !== matchedText.length) {
    return -1;
  }
  return lowerExcerpt.indexOf(lowerMatch);
}

export function locateMatch(excerpt, matchedText, position, contextChars = CONFIG.ui.excerptContextChars) {
  if (typeof excerpt !== 'string' || typeof matchedText !== 'string' || matchedText.length === 0) {
    return -1;
  }
  const validPosition = Number.isInteger(position) && position >= 0;
  const candidates = validPosition ? [position, Math.min(position, contextChars)] : [];
  const hinted = candidates.find((index) => matchesAt(excerpt, matchedText, index));
  if (hinted !== undefined) return hinted;
  const exact = excerpt.indexOf(matchedText);
  return exact >= 0 ? exact : caseInsensitiveIndex(excerpt, matchedText);
}

export function splitExcerpt(excerpt, matchedText, position) {
  const text = typeof excerpt === 'string' ? excerpt : '';
  const start = locateMatch(text, matchedText, position);
  if (start < 0) {
    return text ? [{ text, highlighted: false }] : [];
  }
  const end = start + matchedText.length;
  const segments = [
    { text: text.slice(0, start), highlighted: false },
    { text: text.slice(start, end), highlighted: true },
    { text: text.slice(end), highlighted: false },
  ];
  return segments.filter((segment) => segment.text.length > 0);
}
