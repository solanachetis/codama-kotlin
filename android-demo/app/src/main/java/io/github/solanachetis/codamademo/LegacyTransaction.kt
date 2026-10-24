package io.github.solanachetis.codamademo

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.TransactionInstruction
import java.io.ByteArrayOutputStream

/**
 * Compiles a legacy transaction with empty signature slots, fee payer first.
 *
 * Done by hand because `web3-solana` 0.3.1 `Message.Builder` writes a wrong header when an account is writable
 * in one place and read-only in another (solana-mobile/web3-core#47). Here the wallet is both the Squads
 * `createKey` (read-only signer) and the `creator` (writable signer), which hits exactly that case.
 */
object LegacyTransaction {
    fun compile(feePayer: SolanaPublicKey, blockhash: SolanaPublicKey, instructions: List<TransactionInstruction>): ByteArray {
        class Meta(val key: SolanaPublicKey, var signer: Boolean, var writable: Boolean)
        val metas = LinkedHashMap<String, Meta>()
        fun add(key: SolanaPublicKey, signer: Boolean, writable: Boolean) {
            val m = metas.getOrPut(key.base58()) { Meta(key, false, false) }
            m.signer = m.signer || signer
            m.writable = m.writable || writable
        }
        add(feePayer, signer = true, writable = true)
        instructions.forEach { ix ->
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
        val index = ordered.withIndex().associate { (i, m) -> m.key.base58() to i }

        val out = ByteArrayOutputStream()
        fun shortVec(n: Int) {
            var rest = n
            while (true) {
                val b = rest and 0x7F
                rest = rest ushr 7
                if (rest == 0) { out.write(b); return }
                out.write(b or 0x80)
            }
        }
        val signers = ordered.count { it.signer }
        shortVec(signers)
        repeat(signers) { out.write(ByteArray(64)) }
        out.write(signers)
        out.write(ordered.count { it.signer && !it.writable })
        out.write(ordered.count { !it.signer && !it.writable })
        shortVec(ordered.size)
        ordered.forEach { out.write(it.key.bytes) }
        out.write(blockhash.bytes)
        shortVec(instructions.size)
        for (ix in instructions) {
            out.write(index.getValue(ix.programId.base58()))
            shortVec(ix.accounts.size)
            ix.accounts.forEach { out.write(index.getValue(it.publicKey.base58())) }
            shortVec(ix.data.size)
            out.write(ix.data)
        }
        return out.toByteArray()
    }
}

/** Base58 for transaction signatures (64 bytes), which are not public keys. */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun encode(bytes: ByteArray): String {
        var n = java.math.BigInteger(1, bytes)
        val sb = StringBuilder()
        val base = java.math.BigInteger.valueOf(58)
        while (n.signum() > 0) {
            val (q, r) = n.divideAndRemainder(base)
            sb.append(ALPHABET[r.toInt()])
            n = q
        }
        bytes.takeWhile { it.toInt() == 0 }.forEach { _ -> sb.append('1') }
        return sb.reverse().toString()
    }
}
