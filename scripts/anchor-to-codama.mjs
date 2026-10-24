#!/usr/bin/env node
// Anchor IDL (legacy v00 or v01) -> Codama rootNode JSON, using Codama's official converter.
// Usage: node scripts/anchor-to-codama.mjs <anchor-idl.json> <out-codama.json> [visitors.mjs]
// The optional module default-exports Codama visitors applied after conversion (e.g. PDAs a legacy IDL lacks).
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { rootNodeFromAnchor } from '@codama/nodes-from-anchor';
import { createFromRoot } from 'codama';

const [input, output, visitors] = process.argv.slice(2);
if (!input || !output) {
  console.error('usage: node scripts/anchor-to-codama.mjs <anchor-idl.json> <out-codama.json> [visitors.mjs]');
  process.exit(1);
}

const codama = createFromRoot(rootNodeFromAnchor(JSON.parse(readFileSync(input, 'utf8'))));
if (visitors) {
  for (const v of (await import(pathToFileURL(resolve(visitors)).href)).default) codama.update(v);
}
writeFileSync(output, codama.getJson());
console.log(`Wrote ${output}`);
