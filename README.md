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
- signs and sends it through **Mobile Wallet Adapter** (Seed Vault Wallet on the Seeker, or any MWA wallet);
- reads the new multisig back with the generated `Multisig` decoder.

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
- Handles: integers (u8 to u64, i8 to i64, LE/BE), public keys, booleans, options (variable and fixed/`COption`),
  enums, inline structs, arrays (fixed, prefixed, remainder), size-prefixed bytes, UTF-8 strings,
  omitted discriminators, account defaults (sysvars), optional accounts, multisig remaining signers,
  `isSigner: "either"`.
- Pure-Kotlin binary I/O (no JVM-only API), so the output should port to Kotlin Multiplatform. Not tested yet.

## Proof it is correct

`kotlin-demo` compiles the generated code and checks it **byte for byte** against the official JS
client generated by Codama (`@solana-program/token` 0.18.0, `@solana/kit` 8.5.0):

- 32 instruction cases covering all 28 instructions: program id, data bytes, every account address and signer/writable flag;
- the 3 account layouts: decode, then re-encode to identical bytes;
- error codes and program id;
- 11 PDAs, same address **and** bump: associated token accounts against the official `findAssociatedTokenPda`
  (Token and Token-2022), Squads against Solana Kit's own `getProgramDerivedAddress` and encoders.

```bash
npm install
npm run generate   # IDL -> kotlin-demo/src/main/kotlin/codama/generated/token
npm run vectors    # reference vectors from the official JS client
cd kotlin-demo && ./gradlew test
```

Result on 2026-10-24: `BUILD SUCCESSFUL`. Token: 3 tests passed (32 + 3 + 1 checks). PDAs: 2 tests passed (11 PDAs,
and Squads' program config equals the address read on chain). Squads: the offline test passed; the devnet simulation
(skipped unless `SQUADS_DEVNET=1`) passed again with the generated PDAs.
Regenerating both clients from the IDLs gives exactly the committed code.

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
  enums with data are handled since the Squads work, PDAs since 2026-10-24. Not covered yet: PDA-derived account
  defaults (an instruction filling an account from its PDA by itself), instructions of the additional programs
  (only their PDAs), u128/i128, maps/tuples/sets, instruction parsing (bytes to typed instruction), Token-2022 extensions.
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
renderer/render.mjs            the generator (~600 lines)
scripts/anchor-to-codama.mjs   Anchor IDL -> Codama JSON (@codama/nodes-from-anchor)
scripts/vectors.mjs            reference vectors from the official JS client and Solana Kit
kotlin-demo/                   Gradle project: generated code, Squads helpers, byte-for-byte and devnet tests
android-demo/                  Android app for the Seeker: the generated Squads client, signed with Mobile Wallet Adapter
.github/workflows/ci.yml       regenerates the clients, checks they match the committed code, runs the tests
```
