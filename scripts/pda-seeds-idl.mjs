#!/usr/bin/env node
// A test-only Codama IDL with one PDA per kind of seed the renderer supports, so each code path is checked
// against Solana Kit (scripts/vectors.mjs), not only those Squads and the associated token program happen to use.
// Usage: node scripts/pda-seeds-idl.mjs <out.json>
import { writeFileSync } from 'node:fs';
import {
  bytesTypeNode, bytesValueNode, constantPdaSeedNode, constantPdaSeedNodeFromString, createFromRoot, numberTypeNode,
  numberValueNode, pdaNode, programIdValueNode, programNode, publicKeyTypeNode, publicKeyValueNode, rootNode,
  stringTypeNode, variablePdaSeedNode,
} from 'codama';

const out = process.argv[2];
if (!out) { console.error('usage: node scripts/pda-seeds-idl.mjs <out.json>'); process.exit(1); }

const pdas = [
  pdaNode({ name: 'constantString', seeds: [constantPdaSeedNodeFromString('utf8', 'seed')] }),
  pdaNode({ name: 'constantBytes', seeds: [constantPdaSeedNode(bytesTypeNode(), bytesValueNode('base16', 'deadbeef00ff'))] }),
  pdaNode({ name: 'constantUtf8Bytes', seeds: [constantPdaSeedNode(bytesTypeNode(), bytesValueNode('utf8', 'héllo'))] }),
  pdaNode({ name: 'constantPublicKey', seeds: [constantPdaSeedNode(publicKeyTypeNode(), publicKeyValueNode('SysvarRent111111111111111111111111111111111'))] }),
  pdaNode({ name: 'constantNumber', seeds: [constantPdaSeedNode(numberTypeNode('u16', 'be'), numberValueNode(513))] }),
  pdaNode({ name: 'programIdSeed', seeds: [constantPdaSeedNodeFromString('utf8', 'self'), constantPdaSeedNode(publicKeyTypeNode(), programIdValueNode())] }),
  pdaNode({ name: 'variableString', seeds: [variablePdaSeedNode('label', stringTypeNode('utf8'))] }),
  pdaNode({ name: 'variableBytes', seeds: [variablePdaSeedNode('raw', bytesTypeNode())] }),
  pdaNode({ name: 'variableNumbers', seeds: [
    variablePdaSeedNode('a', numberTypeNode('u16', 'be')),
    variablePdaSeedNode('b', numberTypeNode('u32')),
    variablePdaSeedNode('c', numberTypeNode('i64')),
  ] }),
  pdaNode({ name: 'mixed', seeds: [
    constantPdaSeedNodeFromString('utf8', 'mix'),
    variablePdaSeedNode('owner', publicKeyTypeNode()),
    variablePdaSeedNode('id', numberTypeNode('u8')),
    variablePdaSeedNode('label', stringTypeNode('utf8')),
  ] }),
];

const program = programNode({ name: 'pdaSeeds', publicKey: 'Stake11111111111111111111111111111111111111', pdas });
writeFileSync(out, createFromRoot(rootNode(program)).getJson());
console.log(`Wrote ${pdas.length} test PDAs to ${out}`);
