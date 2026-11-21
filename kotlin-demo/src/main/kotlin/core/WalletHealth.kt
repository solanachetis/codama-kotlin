package core

import codama.generated.token.TokenProgram
import codama.generated.token.accounts.Mint
import codama.generated.token.accounts.Token
import codama.generated.token.instructions.approveCheckedInstruction
import codama.generated.token.instructions.approveInstruction
import codama.generated.token.instructions.revokeInstruction
import com.solana.publickey.SolanaPublicKey
import safety.Alert
import safety.GuardTexts
import safety.TransactionGuard

/** A token account of the wallet whose tokens [delegate] may spend without asking. */
data class OpenPermission(
    val account: SolanaPublicKey,
    val tokenProgram: SolanaPublicKey,
    val mint: SolanaPublicKey,
    val delegate: SolanaPublicKey,
    val delegatedAmount: ULong,
    val decimals: Int?,
) {
    /** As large as the guard's [TransactionGuard.UNLIMITED]: in practice, no limit. */
    val unlimited: Boolean get() = delegatedAmount >= TransactionGuard.UNLIMITED
}

/** Rights a wallet gave others over its token accounts: find them, rate them, remove them. */
class WalletHealth(private val rpc: SolanaRpc) {

    /**
     * Every open spending permission on [owner]'s token accounts (Token and Token-2022), read with the generated
     * `Token` and `Mint` decoders. Token-2022 accounts start with the same layout, so the first bytes are enough.
     * Mint decimals are read in one call for all the mints involved.
     */
    fun openPermissions(owner: SolanaPublicKey): List<OpenPermission> {
        class Found(val address: SolanaPublicKey, val program: SolanaPublicKey, val account: Token)
        val found = listOf(TokenProgram.PROGRAM_ID, TOKEN_2022).flatMap { program ->
            rpc.tokenAccounts(owner, program).mapNotNull { (address, data) ->
                if (data.size < Token.SIZE) return@mapNotNull null
                val account = runCatching { Token.decode(data.copyOf(Token.SIZE)) }.getOrNull() ?: return@mapNotNull null
                if (account.delegate == null || account.delegatedAmount == 0uL) return@mapNotNull null
                if (!account.owner.bytes.contentEquals(owner.bytes)) return@mapNotNull null
                Found(address, program, account)
            }
        }
        if (found.isEmpty()) return emptyList()
        val mints = found.map { it.account.mint }.distinctBy { it.base58() }
        val decimals = mints.zip(rpc.multipleAccountsData(mints)).associate { (mint, data) ->
            mint.base58() to data?.takeIf { it.size >= Mint.SIZE }?.let { runCatching { Mint.decode(it.copyOf(Mint.SIZE)).decimals.toInt() }.getOrNull() }
        }
        return found.map {
            OpenPermission(it.address, it.program, it.account.mint, it.account.delegate!!, it.account.delegatedAmount, decimals[it.account.mint.base58()])
        }
    }

    /** What the guard would say if [owner] gave this permission today: its level is the permission's level. */
    fun rate(p: OpenPermission, owner: SolanaPublicKey, texts: GuardTexts): Alert {
        val approve = p.decimals?.let {
            approveCheckedInstruction(p.account, p.mint, p.delegate, owner, p.delegatedAmount, it.toUByte(), programId = p.tokenProgram)
        } ?: approveInstruction(p.account, p.delegate, owner, p.delegatedAmount, programId = p.tokenProgram)
        return TransactionGuard.check(listOf(approve), owner, texts).single()
    }

    /** An unsigned transaction removing the spending permission on [tokenAccount], paid by [owner]. */
    fun revokeTransaction(owner: SolanaPublicKey, tokenAccount: SolanaPublicKey, tokenProgram: SolanaPublicKey): ByteArray =
        LegacyTransaction.compile(owner, rpc.latestBlockhash(), listOf(revokeInstruction(source = tokenAccount, owner = owner, programId = tokenProgram)))

    companion object {
        val TOKEN_2022: SolanaPublicKey = SolanaPublicKey.from("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
    }
}
