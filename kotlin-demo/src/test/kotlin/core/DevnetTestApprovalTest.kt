package core

import codama.generated.token.TokenProgram
import codama.generated.token.instructions.TokenInstruction
import codama.generated.token.instructions.parseTokenInstruction
import com.solana.publickey.SolanaPublicKey
import safety.Level
import safety.TransactionGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DevnetTestApprovalTest {
    private fun key(i: Int) = SolanaPublicKey(ByteArray(32) { i.toByte() })
    private val me = key(1)
    private val system = SolanaPublicKey.from("11111111111111111111111111111111")

    @Test
    fun addressWithSeedMatchesSolanaWeb3() {
        // Vector from @solana/web3.js PublicKey.createWithSeed tests.
        val derived = DevnetTestApproval.addressWithSeed(system, "limber chicken: 4/45", system)
        assertEquals("9h1HyLCW5dZnBVap8C5egQ9Z6pHyjsh5MNy83iPqqRuq", derived.base58())
    }

    @Test
    fun testApprovalOnlyOwnerSigns() {
        val ixs = DevnetTestApproval.instructions(me, rentMint = 1_461_600, rentAccount = 2_039_280, suffix = "abc12345", delegate = key(66))
        assertEquals(6, ixs.size)
        val signers = ixs.flatMap { ix -> ix.accounts.filter { it.isSigner }.map { it.publicKey.base58() } }.toSet()
        assertEquals(setOf(me.base58()), signers)
        val mint = DevnetTestApproval.addressWithSeed(me, "swg-mint-abc12345", TokenProgram.PROGRAM_ID)
        val account = DevnetTestApproval.addressWithSeed(me, "swg-acct-abc12345", TokenProgram.PROGRAM_ID)
        assertEquals(mint.base58(), ixs[0].accounts[1].publicKey.base58())
        assertEquals(account.base58(), ixs[2].accounts[1].publicKey.base58())
        assertEquals(listOf(system, TokenProgram.PROGRAM_ID, system, TokenProgram.PROGRAM_ID, TokenProgram.PROGRAM_ID, TokenProgram.PROGRAM_ID).map { it.base58() }, ixs.map { it.programId.base58() })
        val approve = assertIs<TokenInstruction.Approve>(parseTokenInstruction(ixs[5]))
        assertEquals(ULong.MAX_VALUE, approve.amount)
        assertEquals(key(66).base58(), approve.delegate.base58())
        assertEquals(account.base58(), approve.source.base58())
        assertIs<TokenInstruction.InitializeMint2>(parseTokenInstruction(ixs[1]))
        assertIs<TokenInstruction.InitializeAccount3>(parseTokenInstruction(ixs[3]))
        assertIs<TokenInstruction.MintTo>(parseTokenInstruction(ixs[4]))
    }

    @Test
    fun createAccountWithSeedIsEncodedLikeTheSystemProgram() {
        val ix = DevnetTestApproval.instructions(me, 1_461_600, 2_039_280, "abc12345", key(66))[0]
        val d = ix.data
        assertEquals(listOf<Byte>(3, 0, 0, 0), d.copyOfRange(0, 4).toList()) // CreateAccountWithSeed
        assertEquals(me.base58(), SolanaPublicKey(d.copyOfRange(4, 36)).base58()) // base
        val seed = "swg-mint-abc12345".toByteArray()
        assertEquals(seed.size.toLong(), (0 until 8).fold(0L) { a, k -> a or ((d[36 + k].toLong() and 0xFF) shl (8 * k)) })
        assertEquals(seed.toList(), d.copyOfRange(44, 44 + seed.size).toList())
        var o = 44 + seed.size
        fun u64(): Long = (0 until 8).fold(0L) { a, k -> a or ((d[o + k].toLong() and 0xFF) shl (8 * k)) }.also { o += 8 }
        assertEquals(1_461_600L, u64())
        assertEquals(82L, u64())
        assertEquals(TokenProgram.PROGRAM_ID.base58(), SolanaPublicKey(d.copyOfRange(o, o + 32)).base58())
        assertEquals(o + 32, d.size)
        // from (payer, signer), new account, base (signer): the base is the payer here, so two accounts.
        assertTrue(ix.accounts[0].isSigner && ix.accounts[0].isWritable)
    }

    @Test
    fun guardFlagsTheTestApprovalAsDanger() {
        val ixs = DevnetTestApproval.instructions(me, 1, 1, "abc12345", key(66))
        assertEquals(Level.DANGER, TransactionGuard.verdict(TransactionGuard.check(ixs, me)))
    }
}
