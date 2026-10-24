#!/usr/bin/env node
// Anchor IDL (legacy v00 or v01) -> Codama rootNode JSON, using Codama's official converter.
// Usage: node scripts/anchor-to-codama.mjs <anchor-idl.json> <out-codama.json>
import { readFileSync, writeFileSync } from 'node:fs';
import { rootNodeFromAnchor } from '@codama/nodes-from-anchor';
import { createFromRoot } from 'codama';

const [input, output] = process.argv.slice(2);
if (!input || !output) {
  console.error('usage: node scripts/anchor-to-codama.mjs <anchor-idl.json> <out-codama.json>');
  process.exit(1);
}

const codama = createFromRoot(rootNodeFromAnchor(JSON.parse(readFileSync(input, 'utf8'))));
writeFileSync(output, codama.getJson());
console.log(`Wrote ${output}`);
