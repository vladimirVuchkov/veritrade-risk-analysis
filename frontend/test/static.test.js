import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

const frontend = new URL('../', import.meta.url);
const read = (path) => readFileSync(new URL(path, frontend), 'utf8');
const html = read('index.html');
const scripts = readdirSync(new URL('js/', frontend)).filter((name) => name.endsWith('.js'));

const attributeValues = (pattern) => [...html.matchAll(pattern)].map((match) => match[1]);

describe('index.html', () => {
  it('declares language, charset and a mobile viewport', () => {
    assert.match(html, /<html lang="en">/);
    assert.match(html, /<meta charset="utf-8">/);
    assert.match(html, /<meta name="viewport" content="width=device-width, initial-scale=1">/);
  });

  it('gives every form control a label and every label a control', () => {
    const labelled = attributeValues(/<label for="([^"]+)"/g);
    const controls = attributeValues(/<(?:input|textarea|select)\s+id="([^"]+)"/g);
    assert.deepEqual([...labelled].sort(), [...controls].sort());
  });

  it('points aria-describedby at existing elements', () => {
    const ids = new Set(attributeValues(/\sid="([^"]+)"/g));
    for (const list of attributeValues(/aria-describedby="([^"]+)"/g)) {
      for (const id of list.split(/\s+/)) assert.ok(ids.has(id), `missing #${id}`);
    }
  });

  it('has unique ids', () => {
    const ids = attributeValues(/\sid="([^"]+)"/g);
    assert.equal(new Set(ids).size, ids.length);
  });

  it('loads only local files that exist and no inline scripts', () => {
    for (const path of attributeValues(/(?:src|href)="([^"#][^"]*)"/g)) {
      assert.ok(existsSync(new URL(path, frontend)), `missing ${path}`);
    }
    assert.doesNotMatch(html, /<script(?![^>]*\bsrc=)[^>]*>/);
    assert.doesNotMatch(html, /\son[a-z]+="/i);
  });

  it('has the Load sample button and the .txt upload', () => {
    assert.match(html, /id="load-sample"[^>]*>Load sample</);
    assert.match(html, /type="file" accept="\.txt,text\/plain"/);
  });
});

describe('JavaScript sources', () => {
  it('never use HTML parsing sinks or eval', () => {
    const sinks = /\.innerHTML|\.outerHTML|insertAdjacentHTML|document\.write|\beval\(|new Function\(/;
    for (const name of [...scripts, '../mock/server.js', '../mock/store.js', '../mock/rules.js']) {
      assert.doesNotMatch(read(`js/${name}`), sinks, name);
    }
  });

  it('use only relative ES module imports so the browser needs no build', () => {
    for (const name of scripts) {
      for (const [, specifier] of read(`js/${name}`).matchAll(/from '([^']+)'/g)) {
        assert.match(specifier, /^\.\/[a-z-]+\.js$/, `${name} imports ${specifier}`);
      }
    }
  });

  it('import cleanly outside the browser except for the DOM wiring', async () => {
    for (const name of scripts.filter((script) => script !== 'app.js')) {
      await import(new URL(`js/${name}`, frontend));
    }
  });

  it('keep the polling interval and limits in the config module only', () => {
    for (const name of scripts.filter((script) => script !== 'config.js')) {
      assert.doesNotMatch(read(`js/${name}`), /\b2000\b|2097152|2 \* 1024 \* 1024/, name);
    }
  });
});
