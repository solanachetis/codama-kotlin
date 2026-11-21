package core

import codama.generated.token.TokenProgram
import codama.generated.token.accounts.Mint
import codama.generated.token.accounts.Token
import codama.generated.token.types.AccountState
import com.solana.publickey.SolanaPublicKey
import safety.GuardTexts
import safety.Level
import safety.TransactionGuard
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WalletHealthTest {
    private fun key(i: Int) = SolanaPublicKey(ByteArray(32) { i.toByte() })
    private val me = key(1)
    private val mint = key(3)
    private val token2022 = SolanaPublicKey.from("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    private fun tokenAccount(owner: SolanaPublicKey = me, delegate: SolanaPublicKey? = null, delegated: ULong = 0uL, mint: SolanaPublicKey = this.mint) =
        Token(mint, owner, 10uL, delegate, AccountState.INITIALIZED, null, delegated, null).encode()

    private val mintData = Mint(me, 1_000uL, 6u, true, null).encode()

    /** Token accounts per token program; mints answered for every getMultipleAccounts address. */
    private class Chain(val accounts: Map<String, List<Pair<SolanaPublicKey, ByteArray>>>, val mintData: ByteArray) : RpcTransport {
        val bodies = mutableListOf<String>()
        override fun post(url: String, jsonBody: String): HttpReply {
            bodies += jsonBody
            val b64 = Base64.getEncoder()
            return when {
                "getTokenAccountsByOwner" in jsonBody -> {
                    val program = accounts.keys.firstOrNull { it in jsonBody }
                    val list = accounts[program].orEmpty().joinToString(",") { (k, d) ->
                        """{"pubkey":"${k.base58()}","account":{"data":["${b64.encodeToString(d)}","base64"]}}"""
                    }
                    FakeTransport.ok("""{"context":{"slot":1},"value":[$list]}""")
                }
                "getMultipleAccounts" in jsonBody -> {
                    val n = Regex("\"[1-9A-HJ-NP-Za-km-z]{32,44}\"").findAll(jsonBody.substringAfter("\"params\":[[").substringBefore("]")).count()
                    FakeTransport.ok("""{"context":{"slot":1},"value":[${List(n) { """{"data":["${b64.encodeToString(mintData)}","base64"]}""" }.joinToString(",")}]}""")
                }
                "getLatestBlockhash" in jsonBody ->
                    FakeTransport.ok("""{"context":{"slot":1},"value":{"blockhash":"${SolanaPublicKey(ByteArray(32) { 9 }).base58()}","lastValidBlockHeight":1}}""")
                else -> error("unexpected call $jsonBody")
            }
        }
    }

    private fun health(chain: Chain) = WalletHealth(SolanaRpc("https://x", chain))

    @Test
    fun keepsOnlyOpenPermissionsOfTheOwner() {
        val chain = Chain(
            mapOf(
                TokenProgram.PROGRAM_ID.base58() to listOf(
                    key(10) to tokenAccount(),
                    key(11) to tokenAccount(delegate = key(66), delegated = 0uL),
                    key(12) to tokenAccount(owner = key(7), delegate = key(66), delegated = 5uL),
                    key(13) to tokenAccount(delegate = key(66), delegated = 2_500_000uL),
                ),
            ),
            mintData,
        )
        val p = health(chain).openPermissions(me).single()
        assertEquals(key(13).base58(), p.account.base58())
        assertEquals(key(66).base58(), p.delegate.base58())
        assertEquals(TokenProgram.PROGRAM_ID.base58(), p.tokenProgram.base58())
        assertEquals(2_500_000uL, p.delegatedAmount)
        assertEquals(6, p.decimals)
    }

    @Test
    fun token2022AccountsAreIncluded() {
        val bigger = tokenAccount(delegate = key(66), delegated = 1uL) + ByteArray(40) // extensions after the base layout
        val chain = Chain(mapOf(token2022.base58() to listOf(key(20) to bigger)), mintData)
        assertEquals(token2022.base58(), health(chain).openPermissions(me).single().tokenProgram.base58())
    }

    @Test
    fun mintsAreReadOnceForAllAccounts() {
        val accounts = List(3) { key(30 + it) to tokenAccount(delegate = key(66), delegated = 1uL) }
        val chain = Chain(mapOf(TokenProgram.PROGRAM_ID.base58() to accounts), mintData)
        assertEquals(3, health(chain).openPermissions(me).size)
        assertEquals(1, chain.bodies.count { "getMultipleAccounts" in it })
        assertEquals(0, chain.bodies.count { "getAccountInfo" in it })
        val mintsAsked = Regex("\"[1-9A-HJ-NP-Za-km-z]{32,44}\"").findAll(chain.bodies.single { "getMultipleAccounts" in it }.substringAfter("[[").substringBefore("]")).count()
        assertEquals(1, mintsAsked, "the same mint is asked once")
    }

    @Test
    fun noMintCallWhenNothingIsOpen() {
        val chain = Chain(mapOf(TokenProgram.PROGRAM_ID.base58() to listOf(key(10) to tokenAccount())), mintData)
        assertTrue(health(chain).openPermissions(me).isEmpty())
        assertEquals(0, chain.bodies.count { "getMultipleAccounts" in it })
    }

    private fun permission(amount: ULong, decimals: Int? = 6) = OpenPermission(key(13), TokenProgram.PROGRAM_ID, mint, key(66), amount, decimals)

    @Test
    fun unlimitedFlagAndDangerRating() {
        val chain = Chain(emptyMap(), mintData)
        val unlimited = permission(ULong.MAX_VALUE)
        assertTrue(unlimited.unlimited)
        assertEquals(Level.DANGER, health(chain).rate(unlimited, me, GuardTexts.EN).level)
        val limited = health(chain).rate(permission(2_500_000uL), me, GuardTexts.EN)
        assertEquals(Level.WARNING, limited.level)
        assertTrue(limited.message.contains("2.5"), limited.message)
        assertTrue(health(chain).rate(permission(42uL, null), me, GuardTexts.EN).message.contains("42 base units"))
    }

    @Test
    fun ratingIsInFrenchWhenAsked() {
        val alert = health(Chain(emptyMap(), mintData)).rate(permission(ULong.MAX_VALUE), me, GuardTexts.FR)
        assertTrue(alert.message.contains("TOUS les jetons"), alert.message)
    }

    @Test
    fun revokeTransactionReadsBackAsOneRevoke() {
        val tx = health(Chain(emptyMap(), mintData)).revokeTransaction(me, key(2), token2022)
        val read = TransactionReader.read(tx)
        assertEquals(me.base58(), read.feePayer.base58())
        assertEquals(Level.INFO, TransactionGuard.check(read.instructions, me).single().level)
        assertEquals(token2022.base58(), read.instructions.single().programId.base58())
        assertTrue(tx.copyOfRange(1, 65).all { it == 0.toByte() }, "unsigned")
    }

    @Test
    fun exampleScamHasThreeDangers() = kotlinx.coroutines.runBlocking {
        val alerts = TransactionGuard.check(TransactionReader.read(ExampleScam.transaction(me)).instructions, me)
        assertEquals(3, alerts.count { it.level == Level.DANGER })
        assertEquals(Level.DANGER, TransactionGuard.verdict(alerts))
        assertEquals(4, alerts.size)
    }
}
