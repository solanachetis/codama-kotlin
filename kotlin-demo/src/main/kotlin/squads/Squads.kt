// Hand-written companion to the generated Squads v4 client: what the IDL can't describe.
// The addresses (PDAs) are generated: their seeds are added to the IDL by idl/squads/pdas.mjs.
package squads

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction

/** Member permissions (bit mask), as in the Squads SDK. */
object Permission {
    const val INITIATE: UByte = 1u
    const val VOTE: UByte = 2u
    const val EXECUTE: UByte = 4u
    const val ALL: UByte = 7u
}

/**
 * The message a vault transaction executes. Squads stores it in its own compact format
 * (`TransactionMessage` in the program): u8 counts, u8 account indexes, u16 data length.
 * The vault is the only signer; it does not pay fees, the member who executes does.
 */
class VaultMessage private constructor(
    val accountKeys: List<SolanaPublicKey>,
    private val numSigners: Int,
    private val numWritableSigners: Int,
    private val numWritableNonSigners: Int,
    /** The instructions the vault will run, with their accounts' signer and writable flags. */
    val instructions: List<TransactionInstruction>,
    /** The address lookup table section, as is (a single 0: none). */
    private val lookupTables: ByteArray = byteArrayOf(0),
) {
    /** Accounts the executor must pass after the fixed ones, in order, with their writable flag. */
    fun remainingAccounts(): List<Pair<SolanaPublicKey, Boolean>> = accountKeys.mapIndexed { i, key ->
        key to (if (i < numSigners) i < numWritableSigners else i - numSigners < numWritableNonSigners)
    }

    fun encode(): ByteArray {
        val out = ArrayList<Byte>()
        fun u8(v: Int) { require(v in 0..255); out += v.toByte() }
        u8(numSigners)
        u8(numWritableSigners)
        u8(numWritableNonSigners)
        u8(accountKeys.size)
        accountKeys.forEach { out.addAll(it.bytes.toList()) }
        u8(instructions.size)
        for (ix in instructions) {
            u8(index(ix.programId))
            u8(ix.accounts.size)
            ix.accounts.forEach { u8(index(it.publicKey)) }
            require(ix.data.size <= 0xFFFF)
            out += ix.data.size.toByte()
            out += (ix.data.size shr 8).toByte()
            out.addAll(ix.data.toList())
        }
        out.addAll(lookupTables.toList())
        return out.toByteArray()
    }

    private fun index(key: SolanaPublicKey) = accountKeys.indexOfFirst { it.bytes.contentEquals(key.bytes) }.also { require(it >= 0) }

    companion object {
        /** Stands for an account loaded from an address lookup table: its address is not in the message. */
        val FROM_LOOKUP_TABLE = SolanaPublicKey(ByteArray(32) { -1 })

        /**
         * Reads a message back from the bytes a `vaultTransactionCreate` carries, to see what a proposal will do.
         * Accounts loaded from address lookup tables appear as [FROM_LOOKUP_TABLE] (writable ones first, as on chain),
         * programs included. Throws on malformed bytes. Only messages without lookup tables can be encoded again.
         */
        fun decode(bytes: ByteArray): VaultMessage {
            var o = 0
            fun u8(): Int { require(o < bytes.size) { "unexpected end of message" }; return bytes[o++].toInt() and 0xFF }
            fun skip(n: Int) { require(o + n <= bytes.size) { "unexpected end of message" }; o += n }
            val numSigners = u8()
            val numWritableSigners = u8()
            val numWritableNonSigners = u8()
            val keys = List(u8()) {
                require(o + 32 <= bytes.size) { "unexpected end of message" }
                SolanaPublicKey(bytes.copyOfRange(o, o + 32)).also { o += 32 }
            }
            fun writable(i: Int) = if (i < numSigners) i < numWritableSigners else i - numSigners < numWritableNonSigners
            class Raw(val program: Int, val accounts: List<Int>, val data: ByteArray)
            val raws = List(u8()) {
                val program = u8()
                val accounts = List(u8()) { u8() }
                val length = u8() or (u8() shl 8)
                require(o + length <= bytes.size) { "unexpected end of message" }
                Raw(program, accounts, bytes.copyOfRange(o, o + length).also { o += length })
            }
            val tablesStart = o
            var loadedWritable = 0
            var loadedReadonly = 0
            repeat(u8()) {
                skip(32)
                u8().also { loadedWritable += it; skip(it) }
                u8().also { loadedReadonly += it; skip(it) }
            }
            require(o == bytes.size) { "extra bytes after the message" }
            fun account(i: Int) = when {
                i < keys.size -> AccountMeta(keys[i], i < numSigners, writable(i))
                i < keys.size + loadedWritable -> AccountMeta(FROM_LOOKUP_TABLE, false, true)
                i < keys.size + loadedWritable + loadedReadonly -> AccountMeta(FROM_LOOKUP_TABLE, false, false)
                else -> throw IllegalArgumentException("account index $i out of range")
            }
            // Unlike a v0 transaction, a Squads message may load even the program from a lookup table.
            val instructions = raws.map { raw -> TransactionInstruction(account(raw.program).publicKey, raw.accounts.map(::account), raw.data) }
            return VaultMessage(keys, numSigners, numWritableSigners, numWritableNonSigners, instructions, bytes.copyOfRange(tablesStart, o))
        }

        fun compile(vault: SolanaPublicKey, instructions: List<TransactionInstruction>): VaultMessage {
            class Meta(val key: SolanaPublicKey, var signer: Boolean, var writable: Boolean)
            val metas = LinkedHashMap<String, Meta>()
            fun add(key: SolanaPublicKey, signer: Boolean, writable: Boolean) {
                val m = metas.getOrPut(key.base58()) { Meta(key, false, false) }
                m.signer = m.signer || signer
                m.writable = m.writable || writable
            }
            add(vault, signer = true, writable = true)
            for (ix in instructions) {
                ix.accounts.forEach { add(it.publicKey, it.isSigner, it.isWritable) }
                add(ix.programId, signer = false, writable = false)
            }
            val all = metas.values.toList()
            val ordered = listOf(all.first()) + all.drop(1).sortedBy {
                when {
                    it.signer && it.writable -> 0
                    it.signer -> 1
                    it.writable -> 2
                    else -> 3
                }
            }
            return VaultMessage(
                accountKeys = ordered.map { it.key },
                numSigners = ordered.count { it.signer },
                numWritableSigners = ordered.count { it.signer && it.writable },
                numWritableNonSigners = ordered.count { !it.signer && it.writable },
                instructions = instructions,
            )
        }
    }
}
