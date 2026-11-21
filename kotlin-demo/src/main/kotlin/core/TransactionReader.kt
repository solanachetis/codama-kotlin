package core

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction
import squads.VaultMessage

/**
 * A transaction read back from its bytes. [usesLookupTables]: some accounts are loaded from address lookup tables;
 * their addresses are not in the bytes, so they appear as [VaultMessage.FROM_LOOKUP_TABLE].
 */
data class ReadTransaction(
    val version: String,
    val feePayer: SolanaPublicKey,
    val instructions: List<TransactionInstruction>,
    val usesLookupTables: Boolean,
)

/**
 * Reads a Solana transaction, legacy or v0, from the bytes a dapp asks a wallet to sign: either the whole
 * transaction (signatures, even empty, then message) or the message alone. Throws [IllegalArgumentException]
 * on anything else.
 */
object TransactionReader {
    fun read(bytes: ByteArray): ReadTransaction {
        require(bytes.isNotEmpty()) { "empty transaction" }
        runCatching {
            val c = Cursor(bytes)
            val n = c.shortVec()
            require(n in 1..127)
            c.skip(64 * n)
            val message = readMessage(c)
            require(message.second == n) { "signature count does not match the message" }
            return message.first
        }
        return readMessage(Cursor(bytes)).first
    }

    /** The transaction and the number of signatures its header requires. Consumes every byte or throws. */
    private fun readMessage(c: Cursor): Pair<ReadTransaction, Int> {
        val first = c.u8()
        val version: String
        val numRequired: Int
        if (first and 0x80 != 0) {
            require(first and 0x7F == 0) { "unsupported version ${first and 0x7F}" }
            version = "v0"
            numRequired = c.u8()
        } else {
            version = "legacy"
            numRequired = first
        }
        val readonlySigned = c.u8()
        val readonlyUnsigned = c.u8()
        val keys = List(c.shortVec()) { SolanaPublicKey(c.take(32)) }
        require(keys.isNotEmpty() && numRequired in 1..keys.size) { "bad message header" }
        require(readonlySigned <= numRequired && readonlyUnsigned <= keys.size - numRequired) { "bad message header" }
        c.skip(32) // recent blockhash

        class Raw(val program: Int, val accounts: List<Int>, val data: ByteArray)
        val raws = List(c.shortVec()) {
            val program = c.u8()
            val accounts = List(c.shortVec()) { c.u8() }
            Raw(program, accounts, c.take(c.shortVec()))
        }

        var loadedWritable = 0
        var loadedReadonly = 0
        if (version == "v0") {
            repeat(c.shortVec()) {
                c.skip(32)
                c.shortVec().also { loadedWritable += it; c.skip(it) }
                c.shortVec().also { loadedReadonly += it; c.skip(it) }
            }
        }
        require(c.atEnd()) { "extra bytes after the message" }

        fun writable(i: Int) = if (i < numRequired) i < numRequired - readonlySigned else i < keys.size - readonlyUnsigned
        fun account(i: Int) = when {
            i < keys.size -> AccountMeta(keys[i], i < numRequired, writable(i))
            i < keys.size + loadedWritable -> AccountMeta(VaultMessage.FROM_LOOKUP_TABLE, false, true)
            i < keys.size + loadedWritable + loadedReadonly -> AccountMeta(VaultMessage.FROM_LOOKUP_TABLE, false, false)
            else -> throw IllegalArgumentException("account index $i out of range")
        }
        val instructions = raws.map { raw ->
            require(raw.program < keys.size) { "program index ${raw.program} out of range" }
            TransactionInstruction(keys[raw.program], raw.accounts.map(::account), raw.data)
        }
        return ReadTransaction(version, keys.first(), instructions, loadedWritable + loadedReadonly > 0) to numRequired
    }

    private class Cursor(private val bytes: ByteArray) {
        private var o = 0
        fun atEnd() = o == bytes.size
        fun u8(): Int {
            require(o < bytes.size) { "unexpected end of transaction" }
            return bytes[o++].toInt() and 0xFF
        }
        fun take(n: Int): ByteArray {
            require(n >= 0 && o + n <= bytes.size) { "unexpected end of transaction" }
            return bytes.copyOfRange(o, o + n).also { o += n }
        }
        fun skip(n: Int) { take(n) }
        /** Compact-u16, as Solana encodes lengths: 7 bits per byte, at most 3 bytes. */
        fun shortVec(): Int {
            var value = 0
            for (shift in 0..14 step 7) {
                val b = u8()
                value = value or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return value
            }
            throw IllegalArgumentException("length too long")
        }
    }
}
