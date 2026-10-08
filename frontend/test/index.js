// Node 22 resolves `node --test frontend/test/` to this directory's index.js instead of
// expanding the directory, so this entry loads every *.test.js file. It does nothing when a
// runner that expands directories executes it as a file of its own, so no test runs twice.
import { readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const testDirectory = fileURLToPath(new URL('.', import.meta.url));
const launchedAsDirectory = resolve(process.argv[1] || '') === resolve(testDirectory);

if (launchedAsDirectory) {
  const testFiles = readdirSync(testDirectory).filter((name) => name.endsWith('.test.js')).sort();
  for (const name of testFiles) {
    await import(new URL(name, import.meta.url));
  }
}
