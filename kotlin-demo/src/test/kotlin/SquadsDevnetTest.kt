import codama.generated.squads.accounts.Multisig
import codama.generated.squads.accounts.ProgramConfig
import codama.generated.squads.instructions.multisigCreateV2Instruction
import codama.generated.squads.instructions.proposalApproveInstruction
import codama.generated.squads.instructions.proposalCreateInstruction
import codama.generated.squads.instructions.vaultTransactionCreateInstruction
import codama.generated.squads.instructions.vaultTransactionExecuteInstruction
import codama.generated.squads.types.Member
import codama.generated.squads.types.Permissions
import codama.generated.squads.types.ProposalVoteArgs
import codama.generated.squads.types.VaultTransactionCreateArgs
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assumptions.assumeTrue
import squads.Permission
import codama.generated.squads.pdas.findMultisigPda
import codama.generated.squads.pdas.findProgramConfigPda
import codama.generated.squads.pdas.findProposalPda
import codama.generated.squads.pdas.findTransactionPda
import codama.generated.squads.pdas.findVaultPda
import squads.VaultMessage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SquadsDevnetTest {

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.encodeToByteArray())

    @Test
    fun anchorDiscriminatorsMatchTheirDefinition() {
        val ix = multisigCreateV2Instruction(
            programConfig = randomKey(), treasury = randomKey(), multisig = randomKey(), createKey = randomKey(),
            creator = randomKey(), configAuthority = null, threshold = 1u, members = emptyList(), timeLock = 0u,
            rentCollector = null, memo = null,
        )
        assertContentEquals(sha256("global:multisig_create_v2").copyOf(8), ix.data.copyOf(8))
        assertContentEquals(sha256("account:Multisig").copyOf(8), Multisig.DISCRIMINATOR)
    }

    /**
     * Whole vault life in ONE transaction, simulated by devnet (nothing is sent, no SOL needed):
     * create a 1-of-3 multisig, fund its vault, propose a transfer out of the vault, approve, execute.
     * Signatures are not checked (sigVerify=false), so a funded devnet account can play the creator.
     * Run with SQUADS_DEVNET=1.
     */
    @Test
    fun fullVaultLifecycleOnDevnet() = runBlocking {
        assumeTrue(System.getenv("SQUADS_DEVNET") == "1", "set SQUADS_DEVNET=1 to run against devnet")

        val configAddress = findProgramConfigPda()
        val config = ProgramConfig.decode(accountData(configAddress) ?: error("no ProgramConfig on devnet"))
        println("ProgramConfig ${configAddress.base58()}: treasury=${config.treasury.base58()} fee=${config.multisigCreationFee}")

        val creator = SolanaPublicKey.from(System.getenv("SQUADS_PAYER") ?: config.treasury.base58())
        println("creator ${creator.base58()} balance=${balance(creator)}")

        val createKey = randomKey()
        val multisig = findMultisigPda(createKey)
        val vault = findVaultPda(multisig, 0u)
        val recipient = randomKey()
        val txIndex = 1uL
        val transaction = findTransactionPda(multisig, txIndex)
        val proposal = findProposalPda(multisig, txIndex)

        val create = multisigCreateV2Instruction(
            programConfig = configAddress,
            treasury = config.treasury,
            multisig = multisig,
            createKey = createKey,
            creator = creator,
            configAuthority = null,
            threshold = 1u,
            members = listOf(
                Member(creator, Permissions(Permission.ALL)),
                Member(randomKey(), Permissions(Permission.VOTE)),
                Member(randomKey(), Permissions(Permission.VOTE)),
            ),
            timeLock = 0u,
            rentCollector = null,
            memo = null,
        )
        val fund = systemTransfer(creator, vault, 10_000_000L)

        val message = VaultMessage.compile(vault, listOf(systemTransfer(vault, recipient, 5_000_000L)))
        val propose = vaultTransactionCreateInstruction(
            multisig = multisig,
            transaction = transaction,
            creator = creator,
            rentPayer = creator,
            args = VaultTransactionCreateArgs(vaultIndex = 0u, ephemeralSigners = 0u, transactionMessage = message.encode(), memo = null),
        )
        val proposalIx = proposalCreateInstruction(multisig, proposal, creator, creator, transactionIndex = txIndex, draft = false)
        val approve = proposalApproveInstruction(multisig, creator, proposal, ProposalVoteArgs(memo = null))
        val execute = vaultTransactionExecuteInstruction(multisig, proposal, transaction, creator).let { ix ->
            ix.copy(accounts = ix.accounts + message.remainingAccounts().map { (key, writable) -> AccountMeta(key, false, writable) })
        }

        val instructions = listOf(create, fund, propose, proposalIx, approve, execute)
        val blockhash = latestBlockhash()
        val viaWeb3 = Transaction(
            Message.Builder().apply { instructions.forEach { addInstruction(it) } }.setRecentBlockhash(blockhash).build(),
        ).serialize()
        val tx = compileLegacy(creator, SolanaPublicKey.from(blockhash), instructions)
        println("web3-solana header: ${header(viaWeb3)}  /  hand-compiled header: ${header(tx)}")
        println("transaction size: ${tx.size} bytes (max 1232)")

        val result = rpc(
            "simulateTransaction",
            """["${Base64.getEncoder().encodeToString(tx)}",{"encoding":"base64","sigVerify":false,"replaceRecentBlockhash":true,
               "accounts":{"encoding":"base64","addresses":["${multisig.base58()}","${recipient.base58()}"]}}]""",
        ).jsonObject["value"]!!.jsonObject
        result["logs"]?.jsonArray?.forEach { println("  " + it.jsonPrimitive.content) }
        assertEquals(JsonNull, result["err"], "simulation failed: ${result["err"]}")

        val accounts = result["accounts"]!!.jsonArray
        val created = Multisig.decode(Base64.getDecoder().decode(accounts[0].jsonObject["data"]!!.jsonArray[0].jsonPrimitive.content))
        println("multisig ${multisig.base58()}: threshold=${created.threshold} members=${created.members.size} txIndex=${created.transactionIndex}")
        assertEquals(1.toUShort(), created.threshold)
        assertEquals(3, created.members.size)
        assertEquals(txIndex, created.transactionIndex)
        val received = accounts[1].jsonObject["lamports"]!!.jsonPrimitive.long
        println("recipient received $received lamports from the vault")
        assertEquals(5_000_000L, received)
        assertTrue(tx.size <= 1232)
    }

    // ---- small helpers (no Solana SDK beyond web3-solana) ----

    /** signatures, then the 3 header bytes and the number of accounts. */
    private fun header(tx: ByteArray): String {
        val sigs = tx[0].toInt()
        val h = 1 + 64 * sigs
        return "sigs=$sigs header=[${tx[h]},${tx[h + 1]},${tx[h + 2]}] accounts=${tx[h + 3]}"
    }

    /** Legacy transaction with empty signature slots; same rules as Talisman's LegacyTransaction (fee payer first). */
    private fun compileLegacy(feePayer: SolanaPublicKey, blockhash: SolanaPublicKey, ixs: List<TransactionInstruction>): ByteArray {
        class M(val key: SolanaPublicKey, var signer: Boolean, var writable: Boolean)
        val metas = LinkedHashMap<String, M>()
        fun add(k: SolanaPublicKey, s: Boolean, w: Boolean) {
            val m = metas.getOrPut(k.base58()) { M(k, false, false) }
            m.signer = m.signer || s; m.writable = m.writable || w
        }
        add(feePayer, true, true)
        ixs.forEach { ix -> ix.accounts.forEach { add(it.publicKey, it.isSigner, it.isWritable) }; add(ix.programId, false, false) }
        val all = metas.values.toList()
        val ordered = listOf(all.first()) + all.drop(1).sortedBy { if (it.signer && it.writable) 0 else if (it.signer) 1 else if (it.writable) 2 else 3 }
        val index = ordered.withIndex().associate { (i, m) -> m.key.base58() to i }
        val out = java.io.ByteArrayOutputStream()
        fun shortVec(n: Int) { var r = n; while (true) { val b = r and 0x7F; r = r ushr 7; if (r == 0) { out.write(b); return }; out.write(b or 0x80) } }
        val signers = ordered.count { it.signer }
        shortVec(signers); repeat(signers) { out.write(ByteArray(64)) }
        out.write(signers); out.write(ordered.count { it.signer && !it.writable }); out.write(ordered.count { !it.signer && !it.writable })
        shortVec(ordered.size); ordered.forEach { out.write(it.key.bytes) }
        out.write(blockhash.bytes)
        shortVec(ixs.size)
        for (ix in ixs) {
            out.write(index.getValue(ix.programId.base58()))
            shortVec(ix.accounts.size); ix.accounts.forEach { out.write(index.getValue(it.publicKey.base58())) }
            shortVec(ix.data.size); out.write(ix.data)
        }
        return out.toByteArray()
    }

    private fun randomKey() = SolanaPublicKey(ByteArray(32).also { SecureRandom().nextBytes(it) })

    private fun systemTransfer(from: SolanaPublicKey, to: SolanaPublicKey, lamports: Long) = TransactionInstruction(
        SolanaPublicKey(ByteArray(32)),
        listOf(AccountMeta(from, true, true), AccountMeta(to, false, true)),
        byteArrayOf(2, 0, 0, 0) + ByteArray(8) { (lamports shr (8 * it)).toByte() },
    )

    private val http = HttpClient.newHttpClient()

    private fun rpc(method: String, params: String): JsonElement {
        val body = """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""
        val response = http.send(
            HttpRequest.newBuilder(URI("https://api.devnet.solana.com"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val json = Json.parseToJsonElement(response.body()).jsonObject
        json["error"]?.let { error("$method: $it") }
        return json["result"]!!
    }

    private fun accountData(address: SolanaPublicKey): ByteArray? {
        val value = rpc("getAccountInfo", """["${address.base58()}",{"encoding":"base64"}]""").jsonObject["value"]
        if (value == null || value is JsonNull) return null
        return Base64.getDecoder().decode((value.jsonObject["data"] as JsonArray)[0].jsonPrimitive.content)
    }

    private fun balance(address: SolanaPublicKey): Long =
        rpc("getBalance", """["${address.base58()}"]""").jsonObject["value"]!!.jsonPrimitive.long

    private fun latestBlockhash(): String =
        (rpc("getLatestBlockhash", "[]").jsonObject["value"] as JsonObject)["blockhash"]!!.jsonPrimitive.content
}
