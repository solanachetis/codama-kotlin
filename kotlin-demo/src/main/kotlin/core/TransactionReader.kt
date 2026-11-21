package core

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction
import squads.VaultMessage

/**
 * A transaction read back from its bytes. [usesLookupTables]: some accounts are loaded from address lookup tables;
 * their addresses are not in the bytes, so they appear as [VaultMessage.FROM_LOOKUP_TABLE].
 * [priorityFeeLamports]: the total priority fee a v1 transaction declares in its header (null when absent or
 * for legacy and v0, where the fee comes from ComputeBudget instructions).
 */
data class ReadTransaction(
    val version: String,
    val feePayer: SolanaPublicKey,
    val instructions: List<TransactionInstruction>,
    val usesLookupTables: Boolean,
    val priorityFeeLamports: ULong? = null,
)

/**
 * Reads a Solana transaction, legacy, v0 or v1, from the bytes a dapp asks a wallet to sign: either the whole
 * transaction or the message alone. Legacy and v0 put the signatures (even empty) first; v1 (SIMD-0385) starts
 * with its version byte 0x81 and puts them last. Throws [IllegalArgumentException] on anything else.
 */
object TransactionReader {
    private const val V1 = 0x81

    fun read(bytes: ByteArray): ReadTransaction {
        require(bytes.isNotEmpty()) { "empty transaction" }
        // Unambiguous: a legacy or v0 transaction starts with a signature count below 128, a legacy message with
        // a signer count below 128, a v0 message with 0x80.
        if (bytes[0].toInt() and 0xFF == V1) return readV1(Cursor(bytes))
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

        val instructions = instructions(keys, numRequired, readonlySigned, readonlyUnsigned, raws, loadedWritable, loadedReadonly)
        return ReadTransaction(version, keys.first(), instructions, loadedWritable + loadedReadonly > 0) to numRequired
    }

    /**
     * v1 (SIMD-0385): version, header, config mask, lifetime, counts, addresses, config values, instruction headers,
     * instruction payloads, then the signatures, which are all there or all absent (the message alone).
     */
    private fun readV1(c: Cursor): ReadTransaction {
        require(c.u8() == V1)
        val numRequired = c.u8()
        val readonlySigned = c.u8()
        val readonlyUnsigned = c.u8()
        val mask = c.u32()
        c.skip(32) // lifetime (recent blockhash)
        val numInstructions = c.u8()
        val keys = List(c.u8()) { SolanaPublicKey(c.take(32)) }
        require(numRequired >= 1 && readonlySigned < numRequired) { "bad message header" }
        require(keys.size >= numRequired + readonlyUnsigned && keys.size <= 64 && numInstructions <= 64) { "bad message header" }
        require(keys.map { it.base58() }.toSet().size == keys.size) { "duplicate address" }
        require(mask and 0b11 == 0 || mask and 0b11 == 0b11) { "priority fee needs both of its bits" }
        val values = c.take(4 * Integer.bitCount(mask))
        val priorityFee = if (mask and 0b11 == 0b11) {
            (0 until 8).fold(0uL) { acc, i -> acc or ((values[i].toULong() and 0xFFuL) shl (8 * i)) }
        } else {
            null
        }
        val headers = List(numInstructions) { Triple(c.u8(), c.u8(), c.u16()) }
        val raws = headers.map { (program, accounts, data) -> Raw(program, List(accounts) { c.u8() }, c.take(data)) }
        val rest = c.remaining()
        require(rest == 0 || rest == 64 * numRequired) { "signatures do not match the message" }
        val instructions = instructions(keys, numRequired, readonlySigned, readonlyUnsigned, raws, 0, 0)
        return ReadTransaction("v1", keys.first(), instructions, usesLookupTables = false, priorityFeeLamports = priorityFee)
    }

    private class Raw(val program: Int, val accounts: List<Int>, val data: ByteArray)

    private fun instructions(
        keys: List<SolanaPublicKey>,
        numRequired: Int,
        readonlySigned: Int,
        readonlyUnsigned: Int,
        raws: List<Raw>,
        loadedWritable: Int,
        loadedReadonly: Int,
    ): List<TransactionInstruction> {
        fun writable(i: Int) = if (i < numRequired) i < numRequired - readonlySigned else i < keys.size - readonlyUnsigned
        fun account(i: Int) = when {
            i < keys.size -> AccountMeta(keys[i], i < numRequired, writable(i))
            i < keys.size + loadedWritable -> AccountMeta(VaultMessage.FROM_LOOKUP_TABLE, false, true)
            i < keys.size + loadedWritable + loadedReadonly -> AccountMeta(VaultMessage.FROM_LOOKUP_TABLE, false, false)
            else -> throw IllegalArgumentException("account index $i out of range")
        }
        return raws.map { raw ->
            require(raw.program < keys.size) { "program index ${raw.program} out of range" }
            TransactionInstruction(keys[raw.program], raw.accounts.map(::account), raw.data)
        }
    }

    private class Cursor(private val bytes: ByteArray) {
        private var o = 0
        fun atEnd() = o == bytes.size
        fun remaining() = bytes.size - o
        fun u8(): Int {
            require(o < bytes.size) { "unexpected end of transaction" }
            return bytes[o++].toInt() and 0xFF
        }
        fun take(n: Int): ByteArray {
            require(n >= 0 && o + n <= bytes.size) { "unexpected end of transaction" }
            return bytes.copyOfRange(o, o + n).also { o += n }
        }
        fun skip(n: Int) { take(n) }
        /** Little-endian, as SIMD-0385 encodes its fixed-width fields. */
        fun u16() = u8() or (u8() shl 8)
        fun u32() = u16() or (u16() shl 16)
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
