import codama.generated.squads.instructions.SquadsMultisigProgramInstruction
import codama.generated.squads.instructions.configTransactionCreateInstruction
import codama.generated.squads.instructions.multisigAddMemberInstruction
import codama.generated.squads.instructions.multisigCreateV2Instruction
import codama.generated.squads.instructions.parseSquadsMultisigProgramInstruction
import codama.generated.squads.instructions.proposalApproveInstruction
import codama.generated.squads.instructions.proposalCreateInstruction
import codama.generated.squads.instructions.spendingLimitUseInstruction
import codama.generated.squads.instructions.vaultTransactionCreateInstruction
import codama.generated.squads.types.ConfigAction
import codama.generated.squads.types.Member
import codama.generated.squads.types.Permissions
import codama.generated.squads.types.ProposalVoteArgs
import codama.generated.squads.types.VaultTransactionCreateArgs
import codama.generated.token.TokenProgram
import codama.generated.token.instructions.TokenInstruction
import codama.generated.token.instructions.parseTokenInstruction
import codama.generated.token.instructions.transferCheckedInstruction
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import squads.Permission
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The generated parsers read an instruction back: which one, its accounts by name, its arguments. Checked by
 * parsing instructions made by the official JS client and rebuilding them to the very same bytes and accounts.
 */
class GeneratedParserTest {
    private fun key(i: Int) = SolanaPublicKey(ByteArray(32) { i.toByte() })

    private fun assertSameInstruction(expected: TransactionInstruction, actual: TransactionInstruction, id: String) {
        assertEquals(expected.programId.base58(), actual.programId.base58(), "$id: program")
        assertEquals(expected.data.toHex(), actual.data.toHex(), "$id: data")
        assertEquals(expected.accounts.size, actual.accounts.size, "$id: account count")
        expected.accounts.zip(actual.accounts).forEachIndexed { i, (e, a) ->
            assertEquals(e.publicKey.base58(), a.publicKey.base58(), "$id: account $i")
            assertEquals(e.isSigner, a.isSigner, "$id: account $i signer")
            assertEquals(e.isWritable, a.isWritable, "$id: account $i writable")
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    /** Every instruction of the official JS client (vectors.json): recognized, then rebuilt byte for byte. */
    @Test
    fun parsesEveryOfficialJsTokenInstruction() {
        val vectors = Json.parseToJsonElement(javaClass.getResource("/vectors.json")!!.readText())
            .jsonObject["instructions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(32, vectors.size)
        for (v in vectors) {
            val id = v.str("id")
            val js = TransactionInstruction(
                SolanaPublicKey.from(v.str("programAddress")),
                v["accounts"]!!.jsonArray.map { it.jsonObject }.map {
                    AccountMeta(SolanaPublicKey.from(it.str("address")), it["isSigner"]!!.jsonPrimitive.boolean, it["isWritable"]!!.jsonPrimitive.boolean)
                },
                unhex(v.str("data")),
            )
            val parsed = assertNotNull(parseTokenInstruction(js), "$id: not recognized")
            // Vector ids start with the instruction name ("transferCheckedMultisig" is a TransferChecked).
            assertTrue(id.lowercase().startsWith(parsed::class.simpleName!!.lowercase()), "$id parsed as ${parsed::class.simpleName}")
            assertSameInstruction(js, parsed.toInstruction(), id)
        }
    }

    @Test
    fun readsTheValuesBack() {
        val ix = transferCheckedInstruction(key(1), key(2), key(3), key(4), amount = 1_500_000uL, decimals = 6u, multiSigners = listOf(key(5), key(6)))
        val parsed = assertIs<TokenInstruction.TransferChecked>(parseTokenInstruction(ix))
        assertEquals(1_500_000uL, parsed.amount)
        assertEquals(6.toUByte(), parsed.decimals)
        assertEquals(key(3).base58(), parsed.destination.base58())
        assertEquals(listOf(key(5).base58(), key(6).base58()), parsed.multiSigners.map { it.base58() })
    }

    /**
     * syncNative's rent account is optional with the "omitted" strategy: left out, not replaced by the program id.
     * Wallets send it with one account (seen in mainnet transactions on 2026-10-25); it must be built and read so.
     */
    @Test
    fun leftOutOptionalAccounts() {
        val withoutRent = codama.generated.token.instructions.syncNativeInstruction(key(1), rent = null)
        assertEquals(1, withoutRent.accounts.size, "the missing rent account is left out")
        val real = TransactionInstruction(TokenProgram.PROGRAM_ID, listOf(AccountMeta(key(1), false, true)), byteArrayOf(0x11))
        val parsed = assertIs<TokenInstruction.SyncNative>(parseTokenInstruction(real))
        assertNull(parsed.rent)
        assertSameInstruction(real, parsed.toInstruction(), "syncNative without rent")
        val withRent = codama.generated.token.instructions.syncNativeInstruction(key(1))
        assertEquals(2, withRent.accounts.size, "the default rent sysvar is kept")
        assertSameInstruction(withRent, assertNotNull(parseTokenInstruction(withRent)).toInstruction(), "syncNative with rent")
    }

    /** Squads: optional accounts, vectors of structs, sealed classes, options, raw bytes. */
    @Test
    fun parsesSquadsInstructions() {
        val cases = mapOf(
            "multisigCreateV2" to multisigCreateV2Instruction(
                key(1), key(2), key(3), key(4), key(5), configAuthority = null, threshold = 2u,
                members = listOf(Member(key(5), Permissions(Permission.ALL)), Member(key(6), Permissions(Permission.VOTE))),
                timeLock = 3600u, rentCollector = key(5), memo = "hello",
            ),
            "configTransactionCreate" to configTransactionCreateInstruction(
                key(1), key(2), key(3), key(3),
                actions = listOf(ConfigAction.AddMember(Member(key(7), Permissions(Permission.VOTE))), ConfigAction.ChangeThreshold(3u)),
                memo = null,
            ),
            "spendingLimitUseSol" to spendingLimitUseInstruction(key(1), key(2), key(3), key(4), key(5), amount = 100uL, decimals = 9u, memo = null, systemProgram = key(0)),
            "spendingLimitUseToken" to spendingLimitUseInstruction(
                key(1), key(2), key(3), key(4), key(5), amount = 7uL, decimals = 6u, memo = "pay",
                mint = key(8), vaultTokenAccount = key(9), destinationTokenAccount = key(10), tokenProgram = TokenProgram.PROGRAM_ID,
            ),
            "proposalCreate" to proposalCreateInstruction(key(1), key(2), key(3), key(3), transactionIndex = 42uL, draft = true),
            "proposalApprove" to proposalApproveInstruction(key(1), key(2), key(3), ProposalVoteArgs(memo = "ok")),
            "vaultTransactionCreate" to vaultTransactionCreateInstruction(
                key(1), key(2), key(3), key(3),
                VaultTransactionCreateArgs(vaultIndex = 0u, ephemeralSigners = 1u, transactionMessage = byteArrayOf(1, 2, 3, 0, -1), memo = null),
            ),
            "multisigAddMember" to multisigAddMemberInstruction(key(1), key(2), Member(key(11), Permissions(Permission.INITIATE)), memo = null, rentPayer = key(2)),
        )
        for ((id, ix) in cases) {
            val parsed = assertNotNull(parseSquadsMultisigProgramInstruction(ix), "$id: not recognized")
            assertTrue(id.lowercase().startsWith(parsed::class.simpleName!!.lowercase()), "$id parsed as ${parsed::class.simpleName}")
            assertSameInstruction(ix, parsed.toInstruction(), id)
        }
        val use = assertIs<SquadsMultisigProgramInstruction.SpendingLimitUse>(parseSquadsMultisigProgramInstruction(cases.getValue("spendingLimitUseSol")))
        assertNull(use.mint, "an absent optional account reads back as null")
        val create = assertIs<SquadsMultisigProgramInstruction.MultisigCreateV2>(parseSquadsMultisigProgramInstruction(cases.getValue("multisigCreateV2")))
        assertEquals(2, create.members.size)
        assertEquals("hello", create.memo)
    }

    /** What must NOT be decoded: another program, a byte too many or too few, missing accounts. */
    @Test
    fun refusesWhatIsNotAnInstructionOfTheProgram() {
        val ix = transferCheckedInstruction(key(1), key(2), key(3), key(4), amount = 5uL, decimals = 2u)
        assertNull(parseTokenInstruction(ix.copy(programId = key(99))), "other program")
        assertNull(parseTokenInstruction(ix.copy(data = ix.data + 0)), "one byte too many")
        assertNull(parseTokenInstruction(ix.copy(data = ix.data.copyOf(ix.data.size - 1))), "one byte missing")
        assertNull(parseTokenInstruction(ix.copy(accounts = ix.accounts.take(3))), "missing account")
        // 0xFF is SPL Token's batch (an empty batch is valid); 200 is used by no instruction.
        assertNull(parseTokenInstruction(ix.copy(data = byteArrayOf(200.toByte()))), "unknown discriminator")
        assertNull(parseSquadsMultisigProgramInstruction(ix), "a Token instruction is not a Squads one")
        // uiAmountToAmount carries text: bytes that are not valid UTF-8 are refused, not turned into "�".
        val uiAmount = codama.generated.token.instructions.uiAmountToAmountInstruction(key(1), uiAmount = "1.5")
        assertIs<TokenInstruction.UiAmountToAmount>(parseTokenInstruction(uiAmount))
        assertNull(parseTokenInstruction(uiAmount.copy(data = uiAmount.data + byteArrayOf(-61))), "invalid UTF-8")
    }

    /** Random data: either refused, or decoded into something that rebuilds to exactly the same bytes. */
    @Test
    fun randomDataNeverDecodesWrong() {
        val random = Random(2026)
        var decoded = 0
        repeat(5_000) {
            val data = ByteArray(random.nextInt(0, 80)).also { random.nextBytes(it) }
            if (random.nextBoolean() && data.isNotEmpty()) data[0] = random.nextInt(0, 40).toByte() // hit real discriminators often
            val accounts = List(random.nextInt(0, 6)) { AccountMeta(key(random.nextInt(1, 50)), random.nextBoolean(), random.nextBoolean()) }
            for ((programId, parse) in listOf<Pair<SolanaPublicKey, (TransactionInstruction) -> Any?>>(
                TokenProgram.PROGRAM_ID to { parseTokenInstruction(it) },
                codama.generated.squads.SquadsMultisigProgram.PROGRAM_ID to { parseSquadsMultisigProgramInstruction(it) },
            )) {
                val ix = TransactionInstruction(programId, accounts, data)
                val parsed = parse(ix) ?: continue
                decoded++
                val rebuilt = when (parsed) {
                    is TokenInstruction -> parsed.toInstruction()
                    is SquadsMultisigProgramInstruction -> parsed.toInstruction()
                    else -> error("unexpected $parsed")
                }
                assertEquals(data.toHex(), rebuilt.data.toHex(), "random data decoded as ${parsed::class.simpleName} must rebuild identically")
            }
        }
        println("random inputs decoded (and rebuilt identically): $decoded")
    }
}
