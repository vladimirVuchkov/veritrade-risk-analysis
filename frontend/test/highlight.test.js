import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';
import { locateMatch, splitExcerpt } from '../js/highlight.js';

const completedExample = JSON.parse(readFileSync(
  new URL('../../docs/contracts/examples/analysis-completed.json', import.meta.url),
  'utf8',
));

const joined = (segments) => segments.map((segment) => segment.text).join('');
const highlighted = (segments) => segments.filter((segment) => segment.highlighted).map((s) => s.text);

describe('splitExcerpt', () => {
  it('highlights a match at the start of the excerpt', () => {
    const segments = splitExcerpt('going concern doubt exists', 'going concern', 0);
    assert.deepEqual(segments, [
      { text: 'going concern', highlighted: true },
      { text: ' doubt exists', highlighted: false },
    ]);
  });

  it('highlights a match at the end of the excerpt', () => {
    const segments = splitExcerpt('we found a material weakness', 'material weakness', 11);
    assert.deepEqual(segments, [
      { text: 'we found a ', highlighted: false },
      { text: 'material weakness', highlighted: true },
    ]);
  });

  it('highlights a match that is the whole excerpt', () => {
    assert.deepEqual(splitExcerpt('data breach', 'data breach', 500), [{ text: 'data breach', highlighted: true }]);
  });

  it('returns the excerpt as plain text when the match is absent', () => {
    const segments = splitExcerpt('nothing to see here', 'litigation', 3);
    assert.deepEqual(segments, [{ text: 'nothing to see here', highlighted: false }]);
  });

  it('returns plain text when matchedText is empty, missing or longer than the excerpt', () => {
    for (const matchedText of ['', undefined, null, 42, 'a much longer text than the excerpt']) {
      assert.deepEqual(splitExcerpt('short', matchedText, 0), [{ text: 'short', highlighted: false }]);
    }
  });

  it('returns no segments for an empty or non-string excerpt', () => {
    for (const excerpt of ['', undefined, null, 7, {}]) {
      assert.deepEqual(splitExcerpt(excerpt, 'x', 0), []);
    }
  });

  it('uses the position to choose between overlapping occurrences', () => {
    assert.deepEqual(highlighted(splitExcerpt('aaa', 'aa', 1)), ['aa']);
    assert.deepEqual(splitExcerpt('aaa', 'aa', 1)[0], { text: 'a', highlighted: false });
    assert.deepEqual(splitExcerpt('aaa', 'aa', 0)[0], { text: 'aa', highlighted: true });
  });

  it('uses the position to choose the right one of repeated occurrences', () => {
    const excerpt = 'risk one; risk two';
    assert.equal(locateMatch(excerpt, 'risk', 10), 10);
    assert.equal(locateMatch(excerpt, 'risk', 0), 0);
  });

  it('uses the context width when the excerpt does not start at the filing start', () => {
    const before = 'x'.repeat(120);
    const excerpt = `${before}risk${'y'.repeat(30)}risk`;
    assert.equal(locateMatch(excerpt, 'risk', 5000), 120);
  });

  it('falls back to the first occurrence when the position does not point at the match', () => {
    assert.equal(locateMatch('abc risk def risk', 'risk', 2), 4);
  });

  it('ignores invalid positions', () => {
    for (const position of [-1, Number.NaN, null, undefined, '4', 1.5, Infinity]) {
      assert.equal(locateMatch('abc risk', 'risk', position), 4);
    }
  });

  it('falls back to a case-insensitive match', () => {
    const segments = splitExcerpt('Pending Litigation is disclosed', 'pending litigation', 0);
    assert.deepEqual(highlighted(segments), ['Pending Litigation']);
  });

  it('does not highlight when lower-casing changes the string length', () => {
    const segments = splitExcerpt('İstanbul office', 'i̇stanbul', 0);
    assert.deepEqual(segments, [{ text: 'İstanbul office', highlighted: false }]);
  });

  it('handles emoji before the match using UTF-16 offsets like Java', () => {
    const excerpt = '🚀🚀 pending litigation 💥';
    const position = excerpt.indexOf('pending');
    assert.equal(position, 5);
    const segments = splitExcerpt(excerpt, 'pending litigation', position);
    assert.deepEqual(segments, [
      { text: '🚀🚀 ', highlighted: false },
      { text: 'pending litigation', highlighted: true },
      { text: ' 💥', highlighted: false },
    ]);
  });

  it('never splits a surrogate pair when the match itself is an emoji', () => {
    const segments = splitExcerpt('alert 🔥 fire', '🔥', 6);
    assert.deepEqual(highlighted(segments), ['🔥']);
    assert.equal(joined(segments), 'alert 🔥 fire');
  });

  it('handles accented and CJK text', () => {
    const segments = splitExcerpt('Société: 訴訟リスク あり', '訴訟リスク', 9);
    assert.deepEqual(highlighted(segments), ['訴訟リスク']);
  });

  it('keeps HTML-like text as plain text segments', () => {
    const excerpt = '<script>alert(1)</script> pending litigation <img src=x onerror=alert(2)>';
    const segments = splitExcerpt(excerpt, 'pending litigation', 26);
    assert.equal(segments[0].text, '<script>alert(1)</script> ');
    assert.equal(segments[2].text, ' <img src=x onerror=alert(2)>');
    assert.equal(joined(segments), excerpt);
  });

  it('highlights a match that itself looks like HTML', () => {
    const segments = splitExcerpt('before <b>bold</b> after', '<b>bold</b>', 7);
    assert.deepEqual(highlighted(segments), ['<b>bold</b>']);
  });

  it('highlights every finding of the contract example exactly', () => {
    for (const finding of completedExample.payload.findings) {
      const segments = splitExcerpt(finding.excerpt, finding.matchedText, finding.position);
      assert.deepEqual(highlighted(segments), [finding.matchedText]);
      assert.equal(joined(segments), finding.excerpt);
    }
  });

  it('always reproduces the original excerpt', () => {
    const cases = [
      ['abc', 'b', 1], ['abc', 'z', 0], ['aaaa', 'aa', 2], ['😀a😀', 'a', 2], ['', 'a', 0], ['x', 'x', 99],
    ];
    for (const [excerpt, matchedText, position] of cases) {
      assert.equal(joined(splitExcerpt(excerpt, matchedText, position)), excerpt);
    }
  });
});
