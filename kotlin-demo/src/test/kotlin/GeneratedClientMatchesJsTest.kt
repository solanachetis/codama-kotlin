import codama.generated.token.TokenProgram
import codama.generated.token.accounts.Mint
import codama.generated.token.accounts.Multisig
import codama.generated.token.accounts.Token
import codama.generated.token.errors.TokenError
import codama.generated.token.instructions.*
import codama.generated.token.types.AccountState
import codama.generated.token.types.AuthorityType
import codama.generated.token.types.BatchDataItem
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.TransactionInstruction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Builds each instruction with the GENERATED Kotlin client and compares it,
 * byte for byte, with the official JS client (@solana-program/token) output
 * stored in vectors.json by scripts/vectors.mjs.
 */
class GeneratedClientMatchesJsTest {

    private val vectors = Json.parseToJsonElement(
        javaClass.getResource("/vectors.json")!!.readText()
    ).jsonObject

    private fun key(i: Int) = SolanaPublicKey(ByteArray(32) { i.toByte() })
    private val source = key(1)
    private val destination = key(2)
    private val mint = key(3)
    private val owner = key(4)
    private val m1 = key(5)
    private val m2 = key(6)
    private val multisig = key(7)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private val kotlinInstructions: Map<String, TransactionInstruction> = mapOf(
        "transfer" to transferInstruction(source, destination, owner, amount = 1_000_000uL),
        "transferChecked" to transferCheckedInstruction(source, mint, destination, owner, amount = ULong.MAX_VALUE, decimals = 9u),
        "transferCheckedMultisig" to transferCheckedInstruction(source, mint, destination, multisig, amount = 42uL, decimals = 6u, multiSigners = listOf(m1, m2)),
        "initializeMintNoFreeze" to initializeMintInstruction(mint, decimals = 6u, mintAuthority = owner),
        "initializeMintWithFreeze" to initializeMintInstruction(mint, decimals = 0u, mintAuthority = owner, freezeAuthority = m1),
        "initializeMint2" to initializeMint2Instruction(mint, decimals = 9u, mintAuthority = owner, freezeAuthority = m2),
        "setAuthorityNone" to setAuthorityInstruction(mint, owner, AuthorityType.FREEZE_ACCOUNT, newAuthority = null),
        "setAuthoritySome" to setAuthorityInstruction(source, owner, AuthorityType.CLOSE_ACCOUNT, newAuthority = m1),
        "mintTo" to mintToInstruction(mint, destination, owner, amount = 7uL),
        "burnChecked" to burnCheckedInstruction(source, mint, owner, amount = 99uL, decimals = 2u),
        "approveChecked" to approveCheckedInstruction(source, mint, m1, owner, amount = 5uL, decimals = 3u),
        "closeAccount" to closeAccountInstruction(source, destination, owner),
        "initializeMultisig" to initializeMultisigInstruction(multisig, m = 2u, signers = listOf(m1, m2)),
        "syncNative" to syncNativeInstruction(source),
        "amountToUiAmount" to amountToUiAmountInstruction(mint, amount = 123_456_789uL),
        "uiAmountToAmount" to uiAmountToAmountInstruction(mint, uiAmount = "1.2345"),
        "withdrawExcessLamports" to withdrawExcessLamportsInstruction(source, destination, owner),
        "unwrapLamportsAll" to unwrapLamportsInstruction(source, destination, owner),
        "unwrapLamportsSome" to unwrapLamportsInstruction(source, destination, owner, amount = 500uL),
        "initializeAccount" to initializeAccountInstruction(source, mint, owner),
        "initializeAccount2" to initializeAccount2Instruction(source, mint, owner),
        "initializeAccount3" to initializeAccount3Instruction(source, mint, owner),
        "approve" to approveInstruction(source, m1, owner, amount = 11uL),
        "revokeMultisig" to revokeInstruction(source, multisig, multiSigners = listOf(m1)),
        "burn" to burnInstruction(source, mint, owner, amount = 3uL),
        "freezeAccount" to freezeAccountInstruction(source, mint, owner),
        "thawAccount" to thawAccountInstruction(source, mint, owner),
        "mintToChecked" to mintToCheckedInstruction(mint, destination, owner, amount = 8uL, decimals = 4u),
        "initializeMultisig2" to initializeMultisig2Instruction(multisig, m = 1u, signers = listOf(m1, m2)),
        "getAccountDataSize" to getAccountDataSizeInstruction(mint),
        "initializeImmutableOwner" to initializeImmutableOwnerInstruction(source),
        "batch" to batchInstruction(listOf(
            BatchDataItem(3u, byteArrayOf(3, 1, 2, 3, 4, 5, 6, 7, 8)),
            BatchDataItem(1u, byteArrayOf(17)),
        )),
    )

    @Test
    fun everyInstructionMatchesTheOfficialJsClient() {
        val expected = vectors["instructions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(expected.size, kotlinInstructions.size, "same number of cases")
        for (vector in expected) {
            val id = vector.str("id")
            val actual = kotlinInstructions[id] ?: error("no Kotlin case for $id")
            assertEquals(vector.str("programAddress"), actual.programId.base58(), "$id: program")
            assertEquals(vector.str("data"), hex(actual.data), "$id: data")
            val accounts = vector["accounts"]!!.jsonArray.map { it.jsonObject }
            assertEquals(accounts.size, actual.accounts.size, "$id: account count")
            accounts.zip(actual.accounts).forEachIndexed { i, (exp, act) ->
                assertEquals(exp.str("address"), act.publicKey.base58(), "$id: account $i address")
                assertEquals(exp["isSigner"]!!.jsonPrimitive.boolean, act.isSigner, "$id: account $i signer")
                assertEquals(exp["isWritable"]!!.jsonPrimitive.boolean, act.isWritable, "$id: account $i writable")
            }
            println("OK  $id")
        }
    }

    @Test
    fun accountsDecodeAndReEncodeLikeTheOfficialJsClient() {
        val data = vectors["accounts"]!!.jsonArray.associate { it.jsonObject.str("id") to unhex(it.jsonObject.str("data")) }

        val m = Mint.decode(data.getValue("mint"))
        assertEquals(owner, m.mintAuthority)
        assertEquals(1_000_000_000uL, m.supply)
        assertEquals(9u.toUByte(), m.decimals)
        assertEquals(true, m.isInitialized)
        assertNull(m.freezeAuthority)
        assertEquals(hex(data.getValue("mint")), hex(m.encode()))
        assertEquals(Mint.SIZE, m.encode().size)

        val t = Token.decode(data.getValue("token"))
        assertEquals(mint, t.mint)
        assertEquals(owner, t.owner)
        assertEquals(77uL, t.amount)
        assertEquals(m1, t.delegate)
        assertEquals(AccountState.FROZEN, t.state)
        assertEquals(2_039_280uL, t.isNative)
        assertEquals(10uL, t.delegatedAmount)
        assertNull(t.closeAuthority)
        assertEquals(hex(data.getValue("token")), hex(t.encode()))

        val ms = Multisig.decode(data.getValue("multisig"))
        assertEquals(2u.toUByte(), ms.m)
        assertEquals(3u.toUByte(), ms.n)
        assertEquals(listOf(key(10), key(11), key(12)), ms.signers.take(3))
        assertEquals(hex(data.getValue("multisig")), hex(ms.encode()))
        println("OK  accounts: Mint, Token, Multisig")
    }

    @Test
    fun errorsAndProgramId() {
        assertEquals("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", TokenProgram.PROGRAM_ID.base58())
        assertEquals(TokenError.INSUFFICIENT_FUNDS, TokenError.fromCode(1))
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
}
