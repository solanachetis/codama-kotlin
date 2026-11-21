// Hand-written on top of the generated parsers: they say WHAT an instruction is, this says whether it can hurt you.
package safety

import codama.generated.squads.instructions.SquadsMultisigProgramInstruction as Squads
import codama.generated.squads.instructions.parseSquadsMultisigProgramInstruction
import codama.generated.squads.types.ConfigAction
import codama.generated.squads.types.Member
import codama.generated.squads.types.Period
import codama.generated.token.instructions.TokenInstruction as Token
import codama.generated.token.instructions.parseTokenInstruction
import codama.generated.token.types.AuthorityType
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.TransactionInstruction
import squads.VaultMessage
import java.math.BigDecimal

/**
 * From least to most serious. [UNCHECKED]: a program this app cannot read, so it says so instead of guessing; most
 * swaps go through such programs, and calling every swap a warning would teach people to ignore warnings.
 */
enum class Level { INFO, UNCHECKED, WARNING, DANGER }

/** One finding about the instruction at [instruction] (0-based), in plain words. */
data class Alert(val level: Level, val message: String, val instruction: Int)

/**
 * Reads every instruction of a transaction before it is signed and flags what can hurt [wallet][check]:
 * giving away a token account, unlimited spending permissions, handing the wallet to a program, multisig changes
 * that skip the members' votes, and anything it cannot read. Squads proposals are opened: what the vault would do
 * if the proposal passes is checked too.
 *
 * It only knows what the instructions say. It doesn't know who an address belongs to, nor what an unknown program
 * does: those are flagged, not judged. Its sentences come from [GuardTexts] (English by default, French available).
 */
object TransactionGuard {
    private const val SYSTEM = "11111111111111111111111111111111"
    private const val TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    // Token-2022 keeps the SPL Token instructions (same bytes) and adds its extensions.
    private const val TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    private const val SQUADS = "SQDS4ep65T869zMMBKyuUq6aD6EgTu8psMjkvj52pCf"
    private const val ASSOCIATED_TOKEN = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    private const val COMPUTE_BUDGET = "ComputeBudget111111111111111111111111111111"
    private val MEMO = setOf("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr", "Memo1UhkJRfHyvLMcVucJwxXeuD728EqVDDwQDxFMNo")
    // Lighthouse: assertions some wallets (Seed Vault Wallet among them) add to make a transaction fail if it does more than shown.
    private const val LIGHTHOUSE = "L2TExMFKdjpN9kozasaurPirfHy9P8sbXoAN1qA3S95"

    /**
     * From this amount on, a spending permission counts as unlimited: drainers often ask for a little less than the
     * maximum so that "unlimited" checks miss it, and nobody needs a permission this large.
     */
    val UNLIMITED: ULong = 1uL shl 63

    fun check(instructions: List<TransactionInstruction>, wallet: SolanaPublicKey, texts: GuardTexts = GuardTexts.EN): List<Alert> =
        instructions.flatMapIndexed { i, ix -> Run(texts).checkOne(ix, wallet, i, prefix = "") }

    /** The most serious level among [alerts], or null if there is nothing to say. */
    fun verdict(alerts: List<Alert>): Level? = alerts.maxOfOrNull { it.level }

    /** One check, in one language. */
    private class Run(val tx: GuardTexts) {
        fun checkOne(ix: TransactionInstruction, me: SolanaPublicKey, i: Int, prefix: String): List<Alert> {
            val out = ArrayList<Alert>()
            fun say(level: Level, message: String) { out += Alert(level, prefix + message, i) }
            when (ix.programId.base58()) {
                TOKEN, TOKEN_2022 -> token(ix, me, ::say)
                SQUADS -> squads(ix, me, i, ::say, out)
                SYSTEM -> system(ix, me, ::say)
                ASSOCIATED_TOKEN -> say(Level.INFO, tx.t("ata.create"))
                COMPUTE_BUDGET -> Unit // fee settings, nothing to fear
                LIGHTHOUSE -> Unit // the wallet's own safety assertions
                in MEMO -> say(Level.INFO, tx.t("memo", ix.data.decodeToString()))
                else -> {
                    val included = if (ix.accounts.any { it.publicKey.base58() == me.base58() }) {
                        tx.t("unknown.included", tx.t(if (prefix.isEmpty()) "who.yours" else "who.vault"))
                    } else ""
                    say(Level.UNCHECKED, tx.t("unknown.program", short(ix.programId), ix.accounts.size, included))
                }
            }
            return out
        }

        // ---------- SPL Token ----------
        private fun token(ix: TransactionInstruction, me: SolanaPublicKey, say: (Level, String) -> Unit) {
            val t = parseTokenInstruction(ix, programId = ix.programId) ?: return when (ix.programId.base58()) {
                TOKEN_2022 -> say(Level.UNCHECKED, tx.t("token2022.unreadable"))
                else -> say(Level.WARNING, tx.t("token.unreadable"))
            }
            when (t) {
                is Token.SetAuthority -> if (t.owner.same(me)) {
                    val to = t.newAuthority
                    when {
                        to == null -> say(Level.WARNING, tx.t("auth.removed", authority(t.authorityType), short(t.owned)))
                        to.same(me) -> say(Level.INFO, tx.t("auth.kept", authority(t.authorityType), short(t.owned)))
                        t.authorityType == AuthorityType.ACCOUNT_OWNER ->
                            say(Level.DANGER, tx.t("auth.owner.given", short(t.owned), short(to)))
                        t.authorityType == AuthorityType.CLOSE_ACCOUNT ->
                            say(Level.WARNING, tx.t("auth.close.given", short(to), short(t.owned)))
                        else -> say(Level.WARNING, tx.t("auth.given", authority(t.authorityType), short(t.owned), short(to)))
                    }
                }
                is Token.Approve -> if (t.owner.same(me)) approve(t.delegate, t.source, t.amount, null, say)
                is Token.ApproveChecked -> if (t.owner.same(me)) approve(t.delegate, t.source, t.amount, t.decimals.toInt(), say)
                is Token.Revoke -> say(Level.INFO, tx.t("revoke", short(t.source)))
                is Token.CloseAccount -> if (t.owner.same(me)) {
                    if (t.destination.same(me)) say(Level.INFO, tx.t("close.back", short(t.account)))
                    else say(Level.WARNING, tx.t("close.elsewhere", short(t.account), short(t.destination)))
                }
                is Token.Transfer -> if (t.authority.same(me)) say(Level.INFO, tx.t("transfer", units(t.amount, null), short(t.source), short(t.destination)))
                is Token.TransferChecked -> if (t.authority.same(me)) say(Level.INFO, tx.t("transfer.checked", units(t.amount, t.decimals.toInt()), short(t.mint), short(t.destination)))
                is Token.Burn -> if (t.authority.same(me)) say(Level.WARNING, tx.t("burn", units(t.amount, null), short(t.account)))
                is Token.BurnChecked -> if (t.authority.same(me)) say(Level.WARNING, tx.t("burn.checked", units(t.amount, t.decimals.toInt()), short(t.mint)))
                else -> Unit // account setup, wrapping SOL, reading amounts: nothing given away
            }
        }

        private fun approve(delegate: SolanaPublicKey, source: SolanaPublicKey, amount: ULong, decimals: Int?, say: (Level, String) -> Unit) {
            if (amount >= UNLIMITED) say(Level.DANGER, tx.t("approve.unlimited", short(delegate), short(source)))
            else say(Level.WARNING, tx.t("approve.limited", short(delegate), units(amount, decimals), short(source)))
        }

        private fun authority(type: AuthorityType) = tx.t(
            when (type) {
                AuthorityType.MINT_TOKENS -> "authority.mint"
                AuthorityType.FREEZE_ACCOUNT -> "authority.freeze"
                AuthorityType.ACCOUNT_OWNER -> "authority.owner"
                AuthorityType.CLOSE_ACCOUNT -> "authority.close"
            },
        )

        // ---------- Squads v4 ----------
        private fun squads(ix: TransactionInstruction, me: SolanaPublicKey, i: Int, say: (Level, String) -> Unit, out: MutableList<Alert>) {
            val s = parseSquadsMultisigProgramInstruction(ix) ?: return say(Level.WARNING, tx.t("squads.unreadable"))
            when (s) {
                is Squads.MultisigCreateV2 -> {
                    say(Level.INFO, tx.t("squads.create", s.threshold, s.members.size))
                    s.configAuthority?.takeUnless { it.same(me) }?.let {
                        say(Level.DANGER, tx.t("squads.create.configAuthority", short(it)))
                    }
                    if (s.members.none { it.key.same(me) }) say(Level.WARNING, tx.t("squads.create.notMember"))
                    if (s.threshold.toInt() == 1 && s.members.size > 1) say(Level.WARNING, tx.t("squads.create.oneVote", s.members.size))
                }
                is Squads.ConfigTransactionCreate -> s.actions.forEach { proposed(it, me, say) }
                is Squads.VaultTransactionCreate -> {
                    val message = runCatching { VaultMessage.decode(s.args.transactionMessage) }.getOrNull()
                    if (message == null) {
                        say(Level.WARNING, tx.t("squads.vault.unreadable"))
                    } else {
                        // What the vault itself would do: the vault is the "wallet" of these instructions.
                        val vault = message.accountKeys.first()
                        message.instructions.forEach { inner -> out += checkOne(inner, vault, i, prefix = tx.t("squads.vault.prefix")) }
                        if (message.instructions.isEmpty()) say(Level.INFO, tx.t("squads.vault.empty"))
                    }
                }
                is Squads.MultisigAddMember -> direct(s.configAuthority, tx.t("squads.direct.addMember", member(s.newMember)), say)
                is Squads.MultisigRemoveMember -> direct(s.configAuthority, tx.t("squads.direct.removeMember", who(s.oldMember, me)), say)
                is Squads.MultisigChangeThreshold -> direct(s.configAuthority, tx.t("squads.direct.changeThreshold", s.newThreshold), say)
                is Squads.MultisigSetTimeLock -> direct(s.configAuthority, tx.t("squads.direct.setTimeLock", s.timeLock), say)
                is Squads.MultisigSetConfigAuthority -> direct(s.configAuthority, tx.t("squads.direct.setConfigAuthority", short(s.configAuthorityArg)), say)
                is Squads.MultisigAddSpendingLimit -> direct(s.configAuthority, tx.t("squads.direct.addSpendingLimit", s.members.size, s.amount, period(s.period)), say)
                is Squads.MultisigRemoveSpendingLimit -> direct(s.configAuthority, tx.t("squads.direct.removeSpendingLimit"), say)
                is Squads.MultisigSetRentCollector -> direct(s.configAuthority, tx.t("squads.direct.setRentCollector", s.rentCollector?.let(::short) ?: tx.t("nobody")), say)
                is Squads.ProposalApprove -> say(Level.INFO, tx.t("squads.approve"))
                is Squads.ProposalReject -> say(Level.INFO, tx.t("squads.reject"))
                // What it runs was decided (and could be checked) when it was proposed; it is not in this transaction.
                is Squads.VaultTransactionExecute, is Squads.ConfigTransactionExecute, is Squads.BatchExecuteTransaction ->
                    say(Level.UNCHECKED, tx.t("squads.execute"))
                is Squads.SpendingLimitUse -> say(Level.INFO, tx.t("squads.spendingLimitUse", units(s.amount, s.decimals.toInt()), short(s.destination)))
                else -> say(Level.INFO, tx.t("squads.other", s::class.simpleName))
            }
        }

        /** A change proposed to the members: it still needs their votes. */
        private fun proposed(action: ConfigAction, me: SolanaPublicKey, say: (Level, String) -> Unit) = when (action) {
            is ConfigAction.AddMember -> say(Level.WARNING, tx.t("proposed.addMember", member(action.newMember)))
            is ConfigAction.RemoveMember -> say(Level.WARNING, tx.t("proposed.removeMember", who(action.oldMember, me)))
            is ConfigAction.ChangeThreshold -> say(Level.WARNING, tx.t("proposed.changeThreshold", action.newThreshold))
            is ConfigAction.SetTimeLock ->
                if (action.newTimeLock == 0u) say(Level.WARNING, tx.t("proposed.removeTimeLock"))
                else say(Level.INFO, tx.t("proposed.timeLock", action.newTimeLock))
            is ConfigAction.AddSpendingLimit -> say(
                Level.WARNING,
                tx.t(
                    "proposed.addSpendingLimit", action.members.size, action.amount, short(action.mint), period(action.period),
                    if (action.destinations.isEmpty()) tx.t("proposed.anyAddress") else "",
                ),
            )
            is ConfigAction.RemoveSpendingLimit -> say(Level.INFO, tx.t("proposed.removeSpendingLimit"))
            is ConfigAction.SetRentCollector -> say(Level.INFO, tx.t("proposed.setRentCollector", action.newRentCollector?.let(::short) ?: tx.t("nobody")))
        }

        /** A change made by the config authority alone: no vote. */
        private fun direct(authority: SolanaPublicKey, what: String, say: (Level, String) -> Unit) =
            say(Level.DANGER, tx.t("squads.direct", short(authority), what))

        private fun member(m: Member) = tx.t("member", short(m.key), permissions(m.permissions.mask))
        private fun who(key: SolanaPublicKey, me: SolanaPublicKey) = if (key.same(me)) tx.t("who.you") else short(key)
        private fun period(p: Period) = tx.t("period.${p.name}")

        private fun permissions(mask: UByte): String = listOfNotNull(
            tx.t("perm.propose").takeIf { mask.toInt() and 1 != 0 },
            tx.t("perm.vote").takeIf { mask.toInt() and 2 != 0 },
            tx.t("perm.execute").takeIf { mask.toInt() and 4 != 0 },
        ).joinToString(" + ").ifEmpty { tx.t("perm.none") }

        // ---------- System program (SOL), read by hand: two instructions matter here ----------
        private fun system(ix: TransactionInstruction, me: SolanaPublicKey, say: (Level, String) -> Unit) {
            val d = ix.data
            if (d.size < 4) return say(Level.WARNING, tx.t("system.unreadable"))
            when (u32(d, 0)) {
                2L -> if (d.size == 12 && ix.accounts.size >= 2) {
                    val from = ix.accounts[0].publicKey
                    val amount = sol(u64(d, 4))
                    if (from.same(me)) say(Level.INFO, tx.t("sol.send", amount, short(ix.accounts[1].publicKey)))
                    else say(Level.INFO, tx.t("sol.move", amount, short(from), short(ix.accounts[1].publicKey)))
                } else say(Level.WARNING, tx.t("sol.malformed"))
                1L -> if (d.size == 36 && ix.accounts.isNotEmpty()) {
                    val owner = SolanaPublicKey(d.copyOfRange(4, 36))
                    val account = ix.accounts[0].publicKey
                    if (account.same(me)) say(Level.DANGER, tx.t("assign.mine", short(owner)))
                    else say(Level.WARNING, tx.t("assign.other", short(account), short(owner)))
                } else say(Level.WARNING, tx.t("assign.malformed"))
                else -> Unit // creating accounts, nonces: nothing given away
            }
        }

        // ---------- formatting ----------
        private fun short(k: SolanaPublicKey) =
            if (k.same(VaultMessage.FROM_LOOKUP_TABLE)) tx.t("lookup.address") else k.base58().let { "${it.take(4)}…${it.takeLast(4)}" }
        private fun units(amount: ULong, decimals: Int?): String =
            if (decimals == null) tx.t("units.base", amount)
            else BigDecimal(amount.toString()).movePointLeft(decimals).stripTrailingZeros().toPlainString()
    }

    private fun SolanaPublicKey.same(other: SolanaPublicKey) = bytes.contentEquals(other.bytes)
    private fun sol(lamports: Long) = BigDecimal(lamports.toULong().toString()).movePointLeft(9).stripTrailingZeros().toPlainString()
    private fun u32(d: ByteArray, o: Int) = (0 until 4).fold(0L) { acc, k -> acc or ((d[o + k].toLong() and 0xFF) shl (8 * k)) }
    private fun u64(d: ByteArray, o: Int) = (0 until 8).fold(0L) { acc, k -> acc or ((d[o + k].toLong() and 0xFF) shl (8 * k)) }
}
