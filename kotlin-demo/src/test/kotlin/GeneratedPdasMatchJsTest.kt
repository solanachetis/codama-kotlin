import codama.generated.squads.pdas.findMultisigPda
import codama.generated.squads.pdas.findProgramConfigPda
import codama.generated.squads.pdas.findProposalPda
import codama.generated.squads.pdas.findSpendingLimitPda
import codama.generated.squads.pdas.findTransactionPda
import codama.generated.squads.pdas.findVaultPda
import codama.generated.token.pdas.findAssociatedTokenPda
import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every generated `find…Pda` against JS (vectors.json from scripts/vectors.mjs): the official
 * `findAssociatedTokenPda` for associated token accounts, Solana Kit's own derivation for Squads.
 * Same address AND same bump.
 */
class GeneratedPdasMatchJsTest {
    private val pdas = Json.parseToJsonElement(javaClass.getResource("/vectors.json")!!.readText())
        .jsonObject["pdas"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.key(k: String) = SolanaPublicKey.from(str(k))

    private suspend fun kotlinPda(vector: JsonObject): ProgramDerivedAddress {
        val s = vector["seeds"]!!.jsonObject
        return when (vector["pda"]?.jsonPrimitive?.content) {
            null -> findAssociatedTokenPda(s.key("owner"), s.key("tokenProgram"), s.key("mint"))
            "programConfig" -> findProgramConfigPda()
            "multisig" -> findMultisigPda(s.key("createKey"))
            "vault" -> findVaultPda(s.key("multisig"), s["index"]!!.jsonPrimitive.int.toUByte())
            "transaction" -> findTransactionPda(s.key("multisig"), s.str("index").toULong())
            "proposal" -> findProposalPda(s.key("multisig"), s.str("transactionIndex").toULong())
            "spendingLimit" -> findSpendingLimitPda(s.key("multisig"), s.key("createKey"))
            else -> error("no Kotlin call for ${vector.str("pda")}")
        }
    }

    @Test
    fun everyPdaMatchesJs() = runBlocking {
        assertEquals(11, pdas.size)
        for (vector in pdas) {
            val id = vector.str("id")
            val pda = kotlinPda(vector)
            assertEquals(vector.str("address"), pda.base58(), "$id: address")
            assertEquals(vector["bump"]!!.jsonPrimitive.int, pda.nonce.toInt(), "$id: bump")
        }
    }

    @Test
    fun squadsProgramConfigIsTheOneOnChain() = runBlocking {
        // Read on mainnet and devnet on 2026-10-24.
        assertEquals("BSTq9w3kZwNwpBXJEvTZz2G9ZTNyKBvoSeXMvwb4cNZr", findProgramConfigPda().base58())
    }
}
