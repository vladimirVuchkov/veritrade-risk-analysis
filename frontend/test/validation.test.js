import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { CONFIG } from '../js/config.js';
import {
  toSubmitRequest,
  utf8ByteLength,
  validateContent,
  validateFiling,
  validateTextFile,
} from '../js/validation.js';

const LIMIT = CONFIG.limits.maxContentBytes;
const valid = { companyName: 'Acme', title: '10-K', content: 'text' };

describe('utf8ByteLength', () => {
  it('counts bytes, not characters', () => {
    assert.equal(utf8ByteLength(''), 0);
    assert.equal(utf8ByteLength('abc'), 3);
    assert.equal(utf8ByteLength('é'), 2);
    assert.equal(utf8ByteLength('€'), 3);
    assert.equal(utf8ByteLength('😀'), 4);
    assert.equal('😀'.length, 2);
  });

  it('counts a lone surrogate as the 3-byte replacement character', () => {
    assert.equal(utf8ByteLength('\uD800'), 3);
  });
});

describe('validateContent', () => {
  it('uses a 2 MB (2,097,152 bytes) limit', () => {
    assert.equal(LIMIT, 2_097_152);
  });

  it('accepts exactly 2 MB of ASCII', () => {
    assert.equal(validateContent('a'.repeat(LIMIT)), null);
  });

  it('rejects 2 MB + 1 byte of ASCII', () => {
    assert.match(validateContent('a'.repeat(LIMIT + 1)), /2097153 bytes; the limit is 2097152/);
  });

  it('accepts exactly 2 MB made of 3-byte characters', () => {
    const text = '€'.repeat(699_050) + 'ab';
    assert.equal(utf8ByteLength(text), LIMIT);
    assert.equal(validateContent(text), null);
  });

  it('rejects 2 MB + 1 byte made of 3-byte characters although it has far fewer characters', () => {
    const text = '€'.repeat(699_050) + 'abc';
    assert.equal(utf8ByteLength(text), LIMIT + 1);
    assert.ok(text.length < LIMIT / 2);
    assert.match(validateContent(text), /limit/);
  });

  it('accepts exactly 2 MB of emoji and rejects one more byte', () => {
    const emoji = '😀'.repeat(LIMIT / 4);
    assert.equal(validateContent(emoji), null);
    assert.match(validateContent(`${emoji}a`), /limit/);
  });

  it('rejects blank or missing content', () => {
    for (const content of ['', ' ', '\n\t  \r\n', ' ', null, undefined, 12]) {
      assert.equal(validateContent(content), 'Filing text is required.');
    }
  });

  it('accepts content with surrounding whitespace', () => {
    assert.equal(validateContent('  text  '), null);
  });
});

describe('validateFiling', () => {
  it('accepts a complete filing', () => {
    assert.deepEqual(validateFiling(valid), { valid: true, errors: {} });
  });

  it('reports every blank field at once', () => {
    const result = validateFiling({ companyName: '  ', title: '', content: '\n' });
    assert.equal(result.valid, false);
    assert.deepEqual(Object.keys(result.errors).sort(), ['companyName', 'content', 'title']);
    assert.equal(result.errors.companyName, 'Company name is required.');
    assert.equal(result.errors.title, 'Title is required.');
  });

  it('reports missing fields', () => {
    assert.deepEqual(Object.keys(validateFiling({}).errors).sort(), ['companyName', 'content', 'title']);
  });

  it('enforces the company name length of 200 characters', () => {
    assert.equal(validateFiling({ ...valid, companyName: 'c'.repeat(200) }).valid, true);
    assert.match(validateFiling({ ...valid, companyName: 'c'.repeat(201) }).errors.companyName, /200/);
  });

  it('enforces the title length of 300 characters', () => {
    assert.equal(validateFiling({ ...valid, title: 't'.repeat(300) }).valid, true);
    assert.match(validateFiling({ ...valid, title: 't'.repeat(301) }).errors.title, /300/);
  });

  it('measures names after trimming', () => {
    assert.equal(validateFiling({ ...valid, companyName: `  ${'c'.repeat(200)}  ` }).valid, true);
  });

  it('reports oversized content only for the content field', () => {
    const result = validateFiling({ ...valid, content: 'a'.repeat(LIMIT + 1) });
    assert.deepEqual(Object.keys(result.errors), ['content']);
  });
});

describe('validateTextFile', () => {
  it('accepts .txt files up to the limit', () => {
    assert.equal(validateTextFile({ name: 'filing.txt', size: 10 }), null);
    assert.equal(validateTextFile({ name: 'FILING.TXT', size: LIMIT }), null);
  });

  it('rejects files over the limit', () => {
    assert.match(validateTextFile({ name: 'big.txt', size: LIMIT + 1 }), /limit/);
  });

  it('rejects other extensions and missing names', () => {
    for (const name of ['filing.pdf', 'filing.txt.exe', 'txt', '', undefined]) {
      assert.equal(validateTextFile({ name, size: 1 }), 'Only .txt files can be uploaded.');
    }
  });
});

describe('toSubmitRequest', () => {
  it('trims the names and keeps the content unchanged', () => {
    const request = toSubmitRequest({ companyName: ' Acme ', title: '\t10-K\n', content: '  body \n' });
    assert.deepEqual(request, { companyName: 'Acme', title: '10-K', content: '  body \n' });
  });
});
