// The production page is served with `Content-Security-Policy: default-src 'self'`: no inline
// scripts or styles, no eval, and every resource from the same origin. These checks fail when a
// change would break the page under that policy.
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

const frontend = new URL('../', import.meta.url);
const read = (path) => readFileSync(new URL(path, frontend), 'utf8');
const scriptNames = readdirSync(new URL('js/', frontend)).filter((name) => name.endsWith('.js'));

const HTML_RULES = Object.freeze([
  ['an inline <script>', /<script\b(?![^>]*\bsrc\s*=)[^>]*>/i],
  ['a <script> with content', /<script\b[^>]*>\s*\S[\s\S]*?<\/script>/i],
  ['a <style> element', /<style\b/i],
  ['a style= attribute', /\sstyle\s*=/i],
  ['an inline event handler', /\son[a-z]+\s*=/i],
  ['a javascript: URL', /javascript:/i],
  ['a cross-origin or data: resource', /\b(?:src|href)\s*=\s*["']?\s*(?:[a-z][a-z0-9+.-]*:|\/\/)/i],
]);

const CSS_RULES = Object.freeze([
  ['a cross-origin or data: url()', /url\(\s*["']?\s*(?:[a-z][a-z0-9+.-]*:|\/\/)/i],
  ['a cross-origin @import', /@import\s+(?:url\()?\s*["']?\s*(?:[a-z][a-z0-9+.-]*:|\/\/)/i],
]);

const JS_RULES = Object.freeze([
  ['eval', /\beval\s*\(/],
  ['new Function', /\bnew\s+Function\s*\(/],
  ['the Function constructor', /(?<![\w.])Function\s*\(/],
  ['setTimeout or setInterval with a string', /\bset(?:Timeout|Interval)\s*\(\s*['"`]/],
  ['a style attribute set through setAttribute', /setAttribute\s*\(\s*['"`]style['"`]/],
  ['a style attribute passed to element()', /\battrs\s*:\s*\{[^}]*\bstyle\s*:/],
  ['an inline event handler attribute', /setAttribute\s*\(\s*['"`]on[a-z]+['"`]/i],
  ['a javascript: URL', /javascript:/i],
  ['an HTML parsing sink', /\.innerHTML|\.outerHTML|insertAdjacentHTML|document\.write/],
]);

function violations(source, rules) {
  return rules.filter(([, pattern]) => pattern.test(source)).map(([name]) => name);
}

describe('CSP readiness (default-src \'self\')', () => {
  it('index.html has no inline script, inline style, event handler or foreign resource', () => {
    assert.deepEqual(violations(read('index.html'), HTML_RULES), []);
  });

  it('styles.css loads nothing from another origin or a data: URL', () => {
    assert.deepEqual(violations(read('styles.css'), CSS_RULES), []);
  });

  it('the JavaScript uses no eval, no string timers and no inline styles or handlers', () => {
    for (const name of scriptNames) {
      assert.deepEqual(violations(read(`js/${name}`), JS_RULES), [], name);
    }
  });

  it('loads the app only as an external module script', () => {
    const scripts = [...read('index.html').matchAll(/<script\b[^>]*>/gi)].map((match) => match[0]);
    assert.deepEqual(scripts, ['<script type="module" src="js/app.js">']);
  });
});

describe('the CSP checks themselves', () => {
  const htmlSamples = {
    'an inline <script>': '<script>alert(1)</script>',
    'a <script> with content': '<script src="a.js">alert(1)</script>',
    'a <style> element': '<style>p{}</style>',
    'a style= attribute': '<p style="color:red">',
    'an inline event handler': '<button onclick="go()">',
    'a javascript: URL': '<a href="javascript:go()">',
    'a cross-origin or data: resource': '<img src="https://cdn.example/x.png">',
  };
  const jsSamples = {
    eval: 'eval("1")',
    'new Function': 'new Function("return 1")',
    'the Function constructor': 'const f = Function("return 1");',
    'setTimeout or setInterval with a string': 'setTimeout("go()", 10)',
    'a style attribute set through setAttribute': "node.setAttribute('style', 'color:red')",
    'a style attribute passed to element()': "element(doc, 'p', { attrs: { style: 'color:red' } })",
    'an inline event handler attribute': "node.setAttribute('onclick', 'go()')",
    'a javascript: URL': "link.href = 'javascript:go()'",
    'an HTML parsing sink': 'node.innerHTML = x',
  };

  it('catch every forbidden HTML pattern', () => {
    for (const [name, sample] of Object.entries(htmlSamples)) {
      assert.ok(violations(sample, HTML_RULES).includes(name), name);
    }
  });

  it('catch every forbidden JavaScript pattern', () => {
    for (const [name, sample] of Object.entries(jsSamples)) {
      assert.ok(violations(sample, JS_RULES).includes(name), name);
    }
  });

  it('catch foreign resources in CSS', () => {
    assert.equal(violations('a { background: url("data:image/png;base64,x") }', CSS_RULES).length, 1);
    assert.ok(violations('@import "https://fonts.example/f.css";', CSS_RULES).includes('a cross-origin @import'));
    assert.deepEqual(violations('a { background: url("img/x.png") }', CSS_RULES), []);
  });

  it('allow what the policy allows', () => {
    assert.deepEqual(violations('<script type="module" src="js/app.js"></script>', HTML_RULES), []);
    assert.deepEqual(violations('<link rel="stylesheet" href="styles.css">', HTML_RULES), []);
    assert.deepEqual(violations("node.style.width = '10px'; setTimeout(wake, ms);", JS_RULES), []);
  });
});
