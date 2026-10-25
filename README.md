# codama-kotlin (prototype)

Generates a typed **Kotlin** client for any Solana program from its **Codama IDL**, on top of
`com.solanamobile:web3-solana` (the library the Solana Mobile docs use for native Android apps).

Status: **prototype**. Two programs tested end to end:

- **SPL Token** (official Codama IDL, `solana-program/token`, commit `f5285693a9` of 2026-08-12): byte-for-byte vs the official JS client.
- **Squads v4 multisig** (official legacy Anchor IDL from `Squads-Protocol/v4`, commit `af94153ff7` of 2026-08-20,
  converted with `@codama/nodes-from-anchor`): 36 instructions, 9 accounts, 45 errors generated. On 2026-10-22 devnet
  simulated a whole vault life built only from this Kotlin code, in one transaction: `MultisigCreateV2` → fund the vault →
  `VaultTransactionCreate` → `ProposalCreate` → `ProposalApprove` → `VaultTransactionExecute` (recipient got 0.005 SOL from the vault).
  `kotlin-demo/src/test/kotlin/SquadsDevnetTest.kt`, run with `SQUADS_DEVNET=1`.
- **On a real Seeker, on mainnet** (2026-10-24): the Android demo below created a Squads multisig with the generated
  `multisigCreateV2Instruction`, signed through Mobile Wallet Adapter by Seed Vault Wallet, then read it back with the
  generated `Multisig` decoder (threshold 1 of 1, permissions 7). Cost: 0.0015 SOL of account rent, no Squads fee.

## Android demo (Seeker)

`android-demo/` is a native Android app (Kotlin, Compose) that compiles the generated sources of `kotlin-demo` as they
are, with no copy:

- reads Squads' `ProgramConfig` with the generated decoder (creation fee, treasury);
- builds `multisigCreateV2Instruction` for a 1-of-1 multisig owned by the wallet (the wallet is also the create key,
  so no extra signer is needed);
- shows **what you will sign**, read back from the instruction's bytes by the generated parser
  (`parseSquadsMultisigProgramInstruction`), not from the values the app meant to put in, with the guard's verdict;
- signs and sends it through **Mobile Wallet Adapter** (Seed Vault Wallet on the Seeker, or any MWA wallet);
- reads the new multisig back with the generated `Multisig` decoder;
- **wallet health**: reads every token account of the wallet (Token and Token-2022) with the generated `Token` and `Mint`
  decoders, lists open spending permissions (`delegate`), and revokes one with the generated `revokeInstruction`, signed by
  the user. On 2026-10-25 it found and removed a real, near-unlimited USDC permission on the author's own wallet;
- **scans an example scam** aimed at the connected wallet (a fake "claim your airdrop" transaction, never signed):
  the guard flags the unlimited spending permission, the token account given away and the wallet handed to a program.

## Transaction guard (`safety/TransactionGuard.kt`)

Hand-written on top of the generated parsers: they say what an instruction is, the guard says whether it can hurt the
wallet that signs it. `TransactionGuard.check(instructions, wallet)` returns plain-English alerts, from least to most
serious: `INFO`, `UNCHECKED` (a program it cannot read: it says so instead of guessing), `WARNING`, `DANGER`.

- **SPL Token and Token-2022** (its SPL Token instructions; extensions are `UNCHECKED`): giving away a token account (`DANGER`), unlimited spending permission (`DANGER`), limited permission,
  closing an account to someone else's profit, burning (`WARNING`); transfers summarized (`INFO`); other people's
  token moves you only pay for are left alone.
- **System program**: SOL transfers (`INFO`), the wallet handed to a program with `Assign` (`DANGER`).
- **Squads v4**: a new multisig with someone else as config authority (`DANGER`: they could change it alone), changes
  made through the config authority without a vote (`DANGER`), proposals to add/remove members, change the threshold,
  remove the time lock or add spending limits (`WARNING`). **Vault proposals are opened**: what the vault would do if
  approved is checked like any transaction ("If approved, the vault: Sends 7 SOL to…"), lookup-table accounts included.
- Unknown programs: `UNCHECKED`. Compute budget and Lighthouse (the wallet's own assertions): no alert.

It only knows what the instructions say: not who an address belongs to, nor what an unknown program does.

Mainnet by default, devnet as an option. The wallet must be on the same network as the app, or it refuses to sign
("network mismatch"). Seed Vault Wallet runs on mainnet.

```bash
cd android-demo && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The transaction is compiled by hand (`LegacyTransaction.kt`): the wallet is both a read-only signer (create key) and a
writable signer (creator), the exact case where `web3-solana` 0.3.1 `Message.Builder` writes a wrong header.

## Squads v4 in Kotlin

```bash
npm run generate:squads   # Anchor IDL -> Codama JSON -> kotlin-demo/src/main/kotlin/codama/generated/squads
```

- Generated: every instruction (`multisigCreateV2Instruction`, `proposalApproveInstruction`…), account decoders
  (`Multisig.decode`, `ProgramConfig.decode`, with discriminator check), `ConfigAction` / `ProposalStatus` as sealed classes,
  and the **6 PDAs** (`findProgramConfigPda`, `findMultisigPda`, `findVaultPda`, `findTransactionPda`, `findProposalPda`,
  `findSpendingLimitPda`). The legacy IDL has no seeds: `idl/squads/pdas.mjs` adds them with Codama's own
  `addPdasVisitor`, same seeds as the official SDK, and the generator renders them like any IDL's PDAs.
- Hand-written (`kotlin-demo/src/main/kotlin/squads/Squads.kt`): `Permission` and `VaultMessage`
  (Squads' compact `TransactionMessage` format + the accounts `vaultTransactionExecute` needs).
- Found on the way: for this 6-instruction transaction, `web3-solana` 0.3.1 `Message.Builder` wrote the header
  `[2,2,4]` instead of `[2,1,3]` and devnet refused it ("failed to sanitize accounts offsets"). The test compiles
  the message by hand instead. Reduced to a minimal case: an account writable in one instruction and read-only in
  another is counted as read-only in the header. Wrong in 0.2.5 and 0.3.1, fixed on `main` (solana-mobile/web3-core#42)
  but only shipped in 0.4.0-beta1. Reported as solana-mobile/web3-core#47.

Generator additions for Squads: Anchor 8-byte discriminators (`fixedSizeTypeNode` + `bytesValueNode`, checked on decode,
exposed as `DISCRIMINATOR`), enums with data → sealed classes, argument/account name clashes (`configAuthorityArg`).

## What it does today

- **28/28 SPL Token instructions** generated, e.g. `transferCheckedInstruction(source, mint, destination, authority, amount, decimals)`.
  For comparison, `web3-solana` 0.3.1 hand-writes 6 of them.
- **Account decoders/encoders**: `Mint`, `Token`, `Multisig` (`Mint.decode(bytes)`, `mint.encode()`).
- **Enums** (`AuthorityType`, `AccountState`) and **program errors** (`TokenError.fromCode(1)`).
- **PDAs**, of the program and of the programs its IDL ships with: `findAssociatedTokenPda(owner, tokenProgram, mint)`
  returns the address and its bump, like Codama's JS `find…Pda`. Seeds: constant strings, bytes, public keys, numbers,
  the program id; variable public keys, strings, bytes, numbers and any other encodable type.
- **Instruction parsing**, the other way round: `parseTokenInstruction(ix)` / `parseSquadsMultisigProgramInstruction(ix)`
  read a `TransactionInstruction` back into a typed value (`TokenInstruction.TransferChecked(source, mint, destination,
  authority, amount, decimals, multiSigners)`), or return null. Recognized by the discriminator, then every data byte
  must be read, there must be enough accounts and text must be valid UTF-8. Each decoded value rebuilds itself with
  `toInstruction()`, to the same bytes. 28/28 Token and 36/36 Squads instructions. The base for showing a user, in
  plain words, what a transaction will do before they sign it.
- Handles: integers (u8 to u64, i8 to i64, LE/BE), public keys, booleans, options (variable and fixed/`COption`),
  enums, inline structs, arrays (fixed, prefixed, remainder), size-prefixed bytes, UTF-8 strings,
  omitted discriminators, account defaults (sysvars), optional accounts (program id in their place, or left out when
  the IDL says `optionalAccountStrategy: "omitted"`, like `syncNative`), multisig remaining signers,
  `isSigner: "either"`.
- Pure-Kotlin binary I/O (no JVM-only API), so the output should port to Kotlin Multiplatform. Not tested yet.

## Proof it is correct

`kotlin-demo` compiles the generated code and checks it **byte for byte** against the official JS
client generated by Codama (`@solana-program/token` 0.18.0, `@solana/kit` 8.5.0):

- 32 instruction cases covering all 28 instructions: program id, data bytes, every account address and signer/writable flag;
- the 3 account layouts: decode, then re-encode to identical bytes;
- error codes and program id;
- 11 PDAs, same address **and** bump: associated token accounts against the official `findAssociatedTokenPda`
  (Token and Token-2022), Squads against Solana Kit's own `getProgramDerivedAddress` and encoders;
- 12 more PDAs from a test-only IDL (`idl/pda-seeds.json`, built by `scripts/pda-seeds-idl.mjs`) with one PDA per kind
  of seed the generator supports, edge values included (empty string, accents, max and negative numbers), against Kit;
- parsing: the 32 instructions made by the official JS client are recognized and rebuilt to the same bytes and accounts;
  8 Squads instructions (optional accounts, sealed classes, options, raw bytes) likewise; other programs, a byte too
  many or too few, missing accounts, unknown discriminators and invalid UTF-8 are refused; 5,000 random inputs are
  either refused or decoded into something that rebuilds identically; `syncNative` with its optional rent account left
  out, as wallets send it on mainnet, is read and rebuilt with one account;
- guard: one test per rule, everyday transactions that must stay quiet, vault proposals opened (with lookup tables),
  and a full drainer (harmless-looking fee + unlimited permission + account given away + wallet assigned) caught on
  its 3 dangerous instructions. On 69 real mainnet Token and Squads transactions (2026-10-25): no DANGER raised;
  swaps through programs it can't read are UNCHECKED, every vault proposal was opened.

```bash
npm install
npm run generate         # SPL Token IDL -> kotlin-demo/src/main/kotlin/codama/generated/token
npm run generate:squads  # Squads Anchor IDL (+ PDAs) -> .../generated/squads
npm run generate:seeds   # test-only PDA IDL -> .../generated/seeds
npm run vectors          # reference vectors from the official JS client and Kit
cd kotlin-demo && ./gradlew test
```

Result on 2026-10-25: `BUILD SUCCESSFUL`. Token: 3 tests passed (32 + 3 + 1 checks). PDAs: 3 tests passed (23 PDAs,
and Squads' program config equals the address read on chain). Parsing: 6 tests passed. Guard: 14 tests passed. Squads: the offline test passed;
the devnet simulation (skipped unless `SQUADS_DEVNET=1`) passed with the generated PDAs.
Regenerating every client from its IDL gives exactly the committed code (CI checks it on each push).

## Using the output on Android

```kotlin
val ix = transferCheckedInstruction(
    source = myTokenAccount,
    mint = usdcMint,
    destination = theirTokenAccount,
    authority = myWallet,       // signs via Mobile Wallet Adapter / Seed Vault
    amount = 1_500_000uL,       // 1.5 USDC
    decimals = 6u,
)
// then build a Message/Transaction with web3-solana and sign it through MWA as usual
```

## Limits (honest list)

- **It is a standalone Node script reading the Codama JSON, not yet a `@codama/renderers-kotlin`
  visitor package.** A real renderer would plug into `codama.accept(renderVisitor(...))` like the JS/Rust/Dart ones.
- **Tested on two programs** (SPL Token byte for byte, Squads v4 on devnet). Anchor 8-byte discriminators and
  enums with data are handled since the Squads work, PDAs since 2026-10-24, instruction parsing since 2026-10-25.
  Not covered yet: PDA-derived account defaults (an instruction filling an account from its PDA by itself),
  instructions of the additional programs (only their PDAs), u128/i128, maps/tuples/sets, Token-2022 extensions,
  plain-language descriptions of parsed instructions (the demo writes its own for `MultisigCreateV2`).
  Parsing needs a discriminator in front of the data (every Anchor and SPL program has one); instructions without
  one are listed as not recognized in the generated KDoc.
  The generator **stops with an error** on any node it does not support instead of producing wrong code.
- No Gradle plugin yet: you run the script, then commit the output.
- The `batch` builder follows the IDL, which declares no accounts. The JS package replaces it with a hand-written helper.
- Instruction-level niceties from the JS client (`provides`, `injected`, identity signer) are not reproduced;
  the Kotlin API just asks for the public keys.

## Layout

```
idl/token.json                 official Codama IDL (input)
idl/squads/                    official Squads v4 Anchor IDL (input)
idl/squads/pdas.mjs            Squads PDA seeds, added to the IDL with Codama visitors
idl/squads.json                the Anchor IDL converted to Codama JSON, with the PDAs
idl/pda-seeds.json             test-only IDL: one PDA per kind of seed (scripts/pda-seeds-idl.mjs)
renderer/render.mjs            the generator (~600 lines)
scripts/anchor-to-codama.mjs   Anchor IDL -> Codama JSON (@codama/nodes-from-anchor)
scripts/vectors.mjs            reference vectors from the official JS client and Solana Kit
kotlin-demo/                   Gradle project: generated code, Squads helpers, byte-for-byte and devnet tests
android-demo/                  Android app for the Seeker: the generated Squads client, signed with Mobile Wallet Adapter
kotlin-demo/src/main/kotlin/safety/   the transaction guard (hand-written, on the generated parsers)
.github/workflows/ci.yml       regenerates the clients, checks they match the committed code, runs the tests
```
