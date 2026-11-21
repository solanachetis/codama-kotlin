package core

import codama.generated.token.instructions.approveInstruction
import codama.generated.token.instructions.revokeInstruction
import codama.generated.token.instructions.setAuthorityInstruction
import codama.generated.token.types.AuthorityType
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.TransactionInstruction
import safety.TransactionGuard
import squads.VaultMessage
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransactionReaderTest {
    private fun key(i: Int) = SolanaPublicKey(ByteArray(32) { i.toByte() })
    private val me = key(1)
    private val system = SolanaPublicKey.from("11111111111111111111111111111111")

    private fun data(ixs: List<TransactionInstruction>) = ixs.map { it.data.toList() }
    private fun accounts(ixs: List<TransactionInstruction>) = ixs.map { ix -> ix.accounts.map { Triple(it.publicKey.base58(), it.isSigner, it.isWritable) } }
    private fun programs(ixs: List<TransactionInstruction>) = ixs.map { it.programId.base58() }

    private val sample = listOf(
        revokeInstruction(key(2), me),
        approveInstruction(key(3), key(66), me, amount = 5uL),
        setAuthorityInstruction(key(3), me, AuthorityType.ACCOUNT_OWNER, key(66)),
    )

    @Test
    fun legacyRoundTripFromCompile() {
        val read = TransactionReader.read(LegacyTransaction.compile(me, key(9), sample))
        assertEquals("legacy", read.version)
        assertEquals(me.base58(), read.feePayer.base58())
        assertFalse(read.usesLookupTables)
        assertEquals(data(sample), data(read.instructions))
        assertEquals(programs(sample), programs(read.instructions))
        assertEquals(sample.map { ix -> ix.accounts.map { it.publicKey.base58() } }, read.instructions.map { ix -> ix.accounts.map { it.publicKey.base58() } })
    }

    @Test
    fun signerAndWritableFlagsComeFromTheHeader() {
        val read = TransactionReader.read(LegacyTransaction.compile(me, key(9), sample))
        val revoke = read.instructions.first()
        // revoke(source, owner): source writable, owner signer
        assertEquals(Triple(key(2).base58(), false, true), Triple(revoke.accounts[0].publicKey.base58(), revoke.accounts[0].isSigner, revoke.accounts[0].isWritable))
        assertTrue(revoke.accounts[1].isSigner)
    }

    @Test
    fun messageAloneIsAccepted() {
        val full = LegacyTransaction.compile(me, key(9), sample)
        val message = full.copyOfRange(1 + 64, full.size)
        val read = TransactionReader.read(message)
        assertEquals("legacy", read.version)
        assertEquals(accounts(TransactionReader.read(full).instructions), accounts(read.instructions))
    }

    @Test
    fun guardSeesTheSameAlertsAfterReading() {
        val read = TransactionReader.read(LegacyTransaction.compile(me, key(9), sample))
        assertEquals(TransactionGuard.check(sample, me), TransactionGuard.check(read.instructions, me))
    }

    /** A v0 message: a SOL transfer from [me] to an account loaded from a lookup table. */
    private fun v0Message(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x80)
        out.write(byteArrayOf(1, 0, 1))
        out.write(2); out.write(me.bytes); out.write(system.bytes)
        out.write(ByteArray(32) { 9 })
        out.write(1) // one instruction
        out.write(1) // program: system
        out.write(2); out.write(0); out.write(2) // accounts: me, first loaded writable
        val transfer = byteArrayOf(2, 0, 0, 0) + ByteArray(8) { (1_000_000L shr (8 * it)).toByte() }
        out.write(transfer.size); out.write(transfer)
        out.write(1) // one lookup table
        out.write(key(50).bytes)
        out.write(1); out.write(5) // writable indexes
        out.write(0) // readonly indexes
        return out.toByteArray()
    }

    @Test
    fun v0WithLookupTableMarksUnresolvedAccounts() {
        for (bytes in listOf(v0Message(), byteArrayOf(1) + ByteArray(64) + v0Message())) {
            val read = TransactionReader.read(bytes)
            assertEquals("v0", read.version)
            assertTrue(read.usesLookupTables)
            assertEquals(me.base58(), read.feePayer.base58())
            val ix = read.instructions.single()
            assertEquals(system.base58(), ix.programId.base58())
            assertTrue(ix.accounts[0].isSigner && ix.accounts[0].isWritable)
            assertEquals(VaultMessage.FROM_LOOKUP_TABLE.base58(), ix.accounts[1].publicKey.base58())
            assertTrue(ix.accounts[1].isWritable)
            assertFalse(ix.accounts[1].isSigner)
        }
    }

    @Test
    fun garbageIsRejected() {
        assertFailsWith<IllegalArgumentException> { TransactionReader.read(byteArrayOf(1, 2, 3)) }
        assertFailsWith<IllegalArgumentException> { TransactionReader.read(ByteArray(0)) }
    }

    @Test
    fun trailingBytesAreRejected() {
        val bytes = LegacyTransaction.compile(me, key(9), listOf(revokeInstruction(key(2), me))) + byteArrayOf(0)
        assertFailsWith<IllegalArgumentException> { TransactionReader.read(bytes) }
    }

    @Test
    fun unknownVersionIsRejected() {
        val message = v0Message().also { it[0] = 0x81.toByte() }
        assertFailsWith<IllegalArgumentException> { TransactionReader.read(message) }
    }

    @Test
    fun accountIndexOutOfRangeIsRejected() {
        val message = v0Message()
        message[message.size - 1 - 1 - 1 - 32 - 1 - 12 - 1 - 1] = 7 // second account index of the instruction → past every key
        assertFailsWith<IllegalArgumentException> { TransactionReader.read(message) }
    }
}
