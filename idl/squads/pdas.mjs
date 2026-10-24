// Squads v4 PDAs, which the legacy Anchor IDL doesn't describe. Same seeds as the official SDK (`pda.ts`),
// checked on devnet on 2026-10-22: every one starts with "multisig".
// Passed to scripts/anchor-to-codama.mjs, which applies these visitors to the converted IDL.
import { addPdasVisitor, constantPdaSeedNodeFromString, numberTypeNode, publicKeyTypeNode, variablePdaSeedNode } from 'codama';

const prefix = constantPdaSeedNodeFromString('utf8', 'multisig');
const text = (s) => constantPdaSeedNodeFromString('utf8', s);
const multisig = variablePdaSeedNode('multisig', publicKeyTypeNode(), ['The multisig account.']);

export default [
  addPdasVisitor({
    squadsMultisigProgram: [
      {
        name: 'programConfig',
        docs: ['Global program settings: creation fee and treasury.'],
        seeds: [prefix, text('program_config')],
      },
      {
        name: 'multisig',
        docs: ['A multisig, found from the key that created it.'],
        seeds: [prefix, text('multisig'), variablePdaSeedNode('createKey', publicKeyTypeNode(), ['The create key used at creation.'])],
      },
      {
        name: 'vault',
        docs: ['A vault of the multisig: it holds the funds, only the program can move them.'],
        seeds: [prefix, multisig, text('vault'), variablePdaSeedNode('index', numberTypeNode('u8'), ['Vault index, 0 for the main vault.'])],
      },
      {
        name: 'transaction',
        docs: ['A transaction of the multisig (vault, config or batch).'],
        seeds: [prefix, multisig, text('transaction'), variablePdaSeedNode('index', numberTypeNode('u64'), ['Transaction index.'])],
      },
      {
        name: 'proposal',
        docs: ['The proposal (votes) attached to a transaction.'],
        seeds: [
          prefix, multisig, text('transaction'),
          variablePdaSeedNode('transactionIndex', numberTypeNode('u64'), ['Index of the transaction voted on.']),
          text('proposal'),
        ],
      },
      {
        name: 'spendingLimit',
        docs: ['A spending limit of the multisig.'],
        seeds: [prefix, multisig, text('spending_limit'), variablePdaSeedNode('createKey', publicKeyTypeNode(), ['The create key of the limit.'])],
      },
    ],
  }),
];
