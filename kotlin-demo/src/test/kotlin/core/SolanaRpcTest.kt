package core

import com.solana.publickey.SolanaPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SolanaRpcTest {
    private fun key(i: Int) = SolanaPublicKey(ByteArray(32) { i.toByte() })
    private fun rpc(t: RpcTransport) = SolanaRpc("https://x", t)

    @Test
    fun rateLimitIsFlagged() {
        val e = assertFailsWith<RpcException> { rpc(FakeTransport(HttpReply(429, "Too many requests"))).latestBlockhash() }
        assertTrue(e.rateLimited)
    }

    @Test
    fun jsonRpcRateLimitCodeIsFlagged() {
        val reply = HttpReply(200, """{"jsonrpc":"2.0","id":1,"error":{"code":429,"message":"slow down"}}""")
        assertTrue(assertFailsWith<RpcException> { rpc(FakeTransport(reply)).latestBlockhash() }.rateLimited)
    }

    @Test
    fun jsonRpcErrorIsNotRateLimit() {
        val reply = HttpReply(200, """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"bad"}}""")
        val e = assertFailsWith<RpcException> { rpc(FakeTransport(reply)).latestBlockhash() }
        assertFalse(e.rateLimited)
        assertTrue(e.message!!.contains("bad"))
    }

    @Test
    fun serverErrorIsRpcFailure() {
        val e = assertFailsWith<RpcException> { rpc(FakeTransport(HttpReply(503, "<html>down</html>"))).latestBlockhash() }
        assertFalse(e.rateLimited)
    }

    @Test
    fun readsUseConfirmedCommitment() {
        val t = FakeTransport(FakeTransport.ok("""{"context":{"slot":1},"value":[]}"""))
        rpc(t).tokenAccounts(key(1), key(2))
        assertTrue(t.bodies.single().contains("\"commitment\":\"confirmed\""), t.bodies.single())
    }

    @Test
    fun latestBlockhashIsDecoded() {
        val hash = key(7).base58()
        val t = FakeTransport(FakeTransport.ok("""{"context":{"slot":1},"value":{"blockhash":"$hash","lastValidBlockHeight":5}}"""))
        assertEquals(hash, rpc(t).latestBlockhash().base58())
        assertTrue(t.bodies.single().contains("\"commitment\":\"confirmed\""))
    }

    @Test
    fun tokenAccountsDecodeBase64() {
        val t = FakeTransport(
            FakeTransport.ok(
                """{"context":{"slot":1},"value":[{"pubkey":"${key(3).base58()}","account":{"data":["AQID","base64"],"owner":"x","lamports":1}}]}""",
            ),
        )
        val (address, data) = rpc(t).tokenAccounts(key(1), key(2)).single()
        assertEquals(key(3).base58(), address.base58())
        assertEquals(listOf<Byte>(1, 2, 3), data.toList())
    }

    @Test
    fun accountDataNullWhenMissing() {
        assertNull(rpc(FakeTransport(FakeTransport.ok("""{"context":{"slot":1},"value":null}"""))).accountData(key(1)))
    }

    @Test
    fun multipleAccountsKeepOrderAndNulls() {
        val t = FakeTransport(FakeTransport.ok("""{"context":{"slot":1},"value":[{"data":["AQ==","base64"]},null]}"""))
        val out = rpc(t).multipleAccountsData(listOf(key(1), key(2)))
        assertEquals(listOf<Byte>(1), out[0]!!.toList())
        assertNull(out[1])
        assertTrue(t.bodies.single().contains("getMultipleAccounts"))
    }

    @Test
    fun multipleAccountsAreBatchedByHundred() {
        val t = object : RpcTransport {
            val bodies = mutableListOf<String>()
            override fun post(url: String, jsonBody: String): HttpReply {
                bodies += jsonBody
                val n = Regex("\"[1-9A-HJ-NP-Za-km-z]{32,44}\"").findAll(jsonBody).count()
                val values = List(n) { "null" }.joinToString(",")
                return FakeTransport.ok("""{"context":{"slot":1},"value":[$values]}""")
            }
        }
        val out = rpc(t).multipleAccountsData(List(150) { key(it) })
        assertEquals(150, out.size)
        assertEquals(2, t.bodies.size)
        assertTrue(rpc(t).multipleAccountsData(emptyList()).isEmpty())
        assertEquals(2, t.bodies.size, "no call for an empty list")
    }

    @Test
    fun statusMapping() {
        fun status(value: String) = rpc(FakeTransport(FakeTransport.ok("""{"context":{"slot":1},"value":[$value]}"""))).status("sig")
        assertNull(status("null"))
        assertEquals("confirmed", status("""{"slot":1,"confirmations":0,"err":null,"confirmationStatus":"confirmed"}"""))
        assertEquals("finalized", status("""{"slot":1,"confirmations":null,"err":null,"confirmationStatus":"finalized"}"""))
        assertTrue(status("""{"slot":1,"err":{"InstructionError":[0,"Custom"]},"confirmationStatus":"confirmed"}""")!!.startsWith("failed"))
    }

    @Test
    fun sendTransactionSendsBase64AndReturnsSignature() {
        val t = FakeTransport(FakeTransport.ok("\"5abc\""))
        val signed = byteArrayOf(1, 2, 3, 4)
        assertEquals("5abc", rpc(t).sendTransaction(signed))
        val body = t.bodies.single()
        assertTrue(body.contains(Base64.getEncoder().encodeToString(signed)), body)
        assertTrue(body.contains("\"encoding\":\"base64\""), body)
    }

    @Test
    fun rentAndBalance() {
        assertEquals(1461600L, rpc(FakeTransport(FakeTransport.ok("1461600"))).minimumBalanceForRentExemption(82))
        assertEquals(42L, rpc(FakeTransport(FakeTransport.ok("""{"context":{"slot":1},"value":42}"""))).balance(key(1)))
    }
}
