const FORBIDDEN = 'HTML parsing APIs must not be used for untrusted text';

class FakeText {
  constructor(text) {
    this.nodeType = 3;
    this.data = String(text);
  }

  get textContent() {
    return this.data;
  }
}

class FakeElement {
  constructor(tagName) {
    this.nodeType = 1;
    this.tagName = tagName.toUpperCase();
    this.attributes = new Map();
    this.childNodes = [];
    this.listeners = new Map();
    this.className = '';
  }

  appendChild(child) {
    if (!(child instanceof FakeElement || child instanceof FakeText)) {
      throw new TypeError('appendChild expects a node');
    }
    this.childNodes.push(child);
    return child;
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
  }

  getAttribute(name) {
    return this.attributes.has(name) ? this.attributes.get(name) : null;
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) || [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  dispatch(type) {
    for (const listener of this.listeners.get(type) || []) listener({ type, target: this });
  }

  get textContent() {
    return this.childNodes.map((child) => child.textContent).join('');
  }

  set textContent(value) {
    this.childNodes = [new FakeText(value)];
  }

  get innerHTML() {
    throw new Error(FORBIDDEN);
  }

  set innerHTML(value) {
    throw new Error(FORBIDDEN);
  }

  set outerHTML(value) {
    throw new Error(FORBIDDEN);
  }

  insertAdjacentHTML() {
    throw new Error(FORBIDDEN);
  }
}

export function createFakeDocument() {
  return {
    createElement: (tagName) => new FakeElement(tagName),
    createTextNode: (text) => new FakeText(text),
  };
}

export function findAll(node, predicate) {
  const matches = [];
  const visit = (current) => {
    if (predicate(current)) matches.push(current);
    for (const child of current.childNodes || []) visit(child);
  };
  visit(node);
  return matches;
}

export function byTag(node, tagName) {
  return findAll(node, (current) => current.tagName === tagName.toUpperCase());
}

export function byClass(node, className) {
  return findAll(node, (current) => (current.className || '').split(' ').includes(className));
}

function escapeText(text) {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function escapeAttribute(text) {
  return escapeText(text).replace(/"/g, '&quot;');
}

export function serialize(node) {
  if (node.nodeType === 3) return escapeText(node.data);
  const tag = node.tagName.toLowerCase();
  const classAttr = node.className ? ` class="${escapeAttribute(node.className)}"` : '';
  const attrs = [...node.attributes].map(([name, value]) => ` ${name}="${escapeAttribute(value)}"`).join('');
  return `<${tag}${classAttr}${attrs}>${node.childNodes.map(serialize).join('')}</${tag}>`;
}
