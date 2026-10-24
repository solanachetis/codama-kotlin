// Hand-written companion to the generated Squads v4 client: what the IDL can't describe.
// The addresses (PDAs) are generated: their seeds are added to the IDL by idl/squads/pdas.mjs.
package squads

import com.solana.publickey.SolanaPublicKey
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
    private val instructions: List<TransactionInstruction>,
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
        u8(0) // no address lookup tables
        return out.toByteArray()
    }

    private fun index(key: SolanaPublicKey) = accountKeys.indexOfFirst { it.bytes.contentEquals(key.bytes) }.also { require(it >= 0) }

    companion object {
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
