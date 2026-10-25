// Hand-written on top of the generated parsers: they say WHAT an instruction is, this says whether it can hurt you.
package safety

import codama.generated.squads.instructions.SquadsMultisigProgramInstruction as Squads
import codama.generated.squads.instructions.parseSquadsMultisigProgramInstruction
import codama.generated.squads.types.ConfigAction
import codama.generated.squads.types.Member
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

/** One finding about the instruction at [instruction] (0-based), in plain English. */
data class Alert(val level: Level, val message: String, val instruction: Int)

/**
 * Reads every instruction of a transaction before it is signed and flags what can hurt [wallet][check]:
 * giving away a token account, unlimited spending permissions, handing the wallet to a program, multisig changes
 * that skip the members' votes, and anything it cannot read. Squads proposals are opened: what the vault would do
 * if the proposal passes is checked too.
 *
 * It only knows what the instructions say. It doesn't know who an address belongs to, nor what an unknown program
 * does: those are flagged, not judged.
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

    fun check(instructions: List<TransactionInstruction>, wallet: SolanaPublicKey): List<Alert> =
        instructions.flatMapIndexed { i, ix -> checkOne(ix, wallet, i, prefix = "") }

    /** The most serious level among [alerts], or null if there is nothing to say. */
    fun verdict(alerts: List<Alert>): Level? = alerts.maxOfOrNull { it.level }

    private fun checkOne(ix: TransactionInstruction, me: SolanaPublicKey, i: Int, prefix: String): List<Alert> {
        val out = ArrayList<Alert>()
        fun say(level: Level, message: String) { out += Alert(level, prefix + message, i) }
        when (ix.programId.base58()) {
            TOKEN, TOKEN_2022 -> token(ix, me, ::say)
            SQUADS -> squads(ix, me, i, ::say, out)
            SYSTEM -> system(ix, me, ::say)
            ASSOCIATED_TOKEN -> say(Level.INFO, "Creates a token account.")
            COMPUTE_BUDGET -> Unit // fee settings, nothing to fear
            LIGHTHOUSE -> Unit // the wallet's own safety assertions
            in MEMO -> say(Level.INFO, "Writes a public note on the blockchain: \"${ix.data.decodeToString()}\".")
            else -> say(
                Level.UNCHECKED,
                "Calls the program ${short(ix.programId)}, which this app cannot check: it could do anything with " +
                    "the ${ix.accounts.size} account(s) it gets${if (ix.accounts.any { it.publicKey.base58() == me.base58() }) ", ${if (prefix.isEmpty()) "yours" else "the vault itself"} included" else ""}.",
            )
        }
        return out
    }

    // ---------- SPL Token ----------
    private fun token(ix: TransactionInstruction, me: SolanaPublicKey, say: (Level, String) -> Unit) {
        val t = parseTokenInstruction(ix, programId = ix.programId) ?: return when (ix.programId.base58()) {
            TOKEN_2022 -> say(Level.UNCHECKED, "A Token-2022 extension instruction this app cannot check.")
            else -> say(Level.WARNING, "A token instruction this app cannot read.")
        }
        when (t) {
            is Token.SetAuthority -> if (t.owner.same(me)) {
                val to = t.newAuthority
                when {
                    to == null -> say(Level.WARNING, "Removes the ${authority(t.authorityType)} of ${short(t.owned)} forever. This cannot be undone.")
                    to.same(me) -> say(Level.INFO, "Keeps the ${authority(t.authorityType)} of ${short(t.owned)} with you.")
                    t.authorityType == AuthorityType.ACCOUNT_OWNER ->
                        say(Level.DANGER, "Gives your token account ${short(t.owned)} to ${short(to)}: they will own every token in it.")
                    t.authorityType == AuthorityType.CLOSE_ACCOUNT ->
                        say(Level.WARNING, "Lets ${short(to)} close your token account ${short(t.owned)} and keep its SOL.")
                    else -> say(Level.WARNING, "Gives the ${authority(t.authorityType)} of ${short(t.owned)} to ${short(to)}.")
                }
            }
            is Token.Approve -> if (t.owner.same(me)) approve(t.delegate, t.source, t.amount, null, say)
            is Token.ApproveChecked -> if (t.owner.same(me)) approve(t.delegate, t.source, t.amount, t.decimals.toInt(), say)
            is Token.Revoke -> say(Level.INFO, "Removes a spending permission on ${short(t.source)}.")
            is Token.CloseAccount -> if (t.owner.same(me)) {
                if (t.destination.same(me)) say(Level.INFO, "Closes your token account ${short(t.account)}; its SOL comes back to you.")
                else say(Level.WARNING, "Closes your token account ${short(t.account)} and sends its SOL to ${short(t.destination)}.")
            }
            is Token.Transfer -> if (t.authority.same(me)) say(Level.INFO, "Sends ${units(t.amount, null)} from ${short(t.source)} to ${short(t.destination)}.")
            is Token.TransferChecked -> if (t.authority.same(me)) say(Level.INFO, "Sends ${units(t.amount, t.decimals.toInt())} tokens of ${short(t.mint)} to ${short(t.destination)}.")
            is Token.Burn -> if (t.authority.same(me)) say(Level.WARNING, "Destroys ${units(t.amount, null)} from ${short(t.account)}. This cannot be undone.")
            is Token.BurnChecked -> if (t.authority.same(me)) say(Level.WARNING, "Destroys ${units(t.amount, t.decimals.toInt())} tokens of ${short(t.mint)}. This cannot be undone.")
            else -> Unit // account setup, wrapping SOL, reading amounts: nothing given away
        }
    }

    private fun approve(delegate: SolanaPublicKey, source: SolanaPublicKey, amount: ULong, decimals: Int?, say: (Level, String) -> Unit) {
        if (amount == ULong.MAX_VALUE) say(Level.DANGER, "Lets ${short(delegate)} spend ALL the tokens of ${short(source)}, with no limit, without asking you again.")
        else say(Level.WARNING, "Lets ${short(delegate)} spend up to ${units(amount, decimals)} from ${short(source)} without asking you again.")
    }

    private fun authority(type: AuthorityType) = when (type) {
        AuthorityType.MINT_TOKENS -> "right to create new tokens"
        AuthorityType.FREEZE_ACCOUNT -> "right to freeze accounts"
        AuthorityType.ACCOUNT_OWNER -> "ownership"
        AuthorityType.CLOSE_ACCOUNT -> "right to close the account"
    }

    // ---------- Squads v4 ----------
    private fun squads(ix: TransactionInstruction, me: SolanaPublicKey, i: Int, say: (Level, String) -> Unit, out: MutableList<Alert>) {
        val s = parseSquadsMultisigProgramInstruction(ix) ?: return say(Level.WARNING, "A Squads instruction this app cannot read.")
        when (s) {
            is Squads.MultisigCreateV2 -> {
                say(Level.INFO, "Creates a Squads multisig: ${s.threshold} vote(s) needed out of ${s.members.size} member(s).")
                s.configAuthority?.takeUnless { it.same(me) }?.let {
                    say(Level.DANGER, "${short(it)} will be able to change this multisig alone (config authority), without the members' votes.")
                }
                if (s.members.none { it.key.same(me) }) say(Level.WARNING, "You are not a member of this new multisig.")
                if (s.threshold.toInt() == 1 && s.members.size > 1) say(Level.WARNING, "Any single member will be able to move the funds (1 vote out of ${s.members.size}).")
            }
            is Squads.ConfigTransactionCreate -> s.actions.forEach { proposed(it, me, say) }
            is Squads.VaultTransactionCreate -> {
                val message = runCatching { VaultMessage.decode(s.args.transactionMessage) }.getOrNull()
                if (message == null) {
                    say(Level.WARNING, "Proposes a vault transaction this app cannot open: check it elsewhere before voting.")
                } else {
                    // What the vault itself would do: the vault is the "wallet" of these instructions.
                    val vault = message.accountKeys.first()
                    message.instructions.forEach { inner -> out += checkOne(inner, vault, i, prefix = "If approved, the vault: ") }
                    if (message.instructions.isEmpty()) say(Level.INFO, "Proposes an empty vault transaction.")
                }
            }
            is Squads.MultisigAddMember -> direct(s.configAuthority, "adds ${member(s.newMember)}", say)
            is Squads.MultisigRemoveMember -> direct(s.configAuthority, "removes ${who(s.oldMember, me)}", say)
            is Squads.MultisigChangeThreshold -> direct(s.configAuthority, "sets the votes needed to ${s.newThreshold}", say)
            is Squads.MultisigSetTimeLock -> direct(s.configAuthority, "sets the time lock to ${s.timeLock} s", say)
            is Squads.MultisigSetConfigAuthority -> direct(s.configAuthority, "gives the config authority to ${short(s.configAuthorityArg)}", say)
            is Squads.MultisigAddSpendingLimit -> direct(s.configAuthority, "lets ${s.members.size} member(s) spend up to ${s.amount} base units per ${s.period.name.lowercase()} without a vote", say)
            is Squads.MultisigRemoveSpendingLimit -> direct(s.configAuthority, "removes a spending limit", say)
            is Squads.MultisigSetRentCollector -> direct(s.configAuthority, "sends the multisig's rent to ${s.rentCollector?.let(::short) ?: "nobody"}", say)
            is Squads.ProposalApprove -> say(Level.INFO, "Votes YES on a proposal.")
            is Squads.ProposalReject -> say(Level.INFO, "Votes NO on a proposal.")
            // What it runs was decided (and could be checked) when it was proposed; it is not in this transaction.
            is Squads.VaultTransactionExecute, is Squads.ConfigTransactionExecute, is Squads.BatchExecuteTransaction ->
                say(Level.UNCHECKED, "Executes an approved proposal: what it does was decided earlier and is not shown in this transaction.")
            is Squads.SpendingLimitUse -> say(Level.INFO, "Spends ${units(s.amount, s.decimals.toInt())} from the vault, within a spending limit, to ${short(s.destination)}.")
            else -> say(Level.INFO, "Squads: ${s::class.simpleName}.")
        }
    }

    /** A change proposed to the members: it still needs their votes. */
    private fun proposed(action: ConfigAction, me: SolanaPublicKey, say: (Level, String) -> Unit) = when (action) {
        is ConfigAction.AddMember -> say(Level.WARNING, "Proposes to add ${member(action.newMember)} to the multisig.")
        is ConfigAction.RemoveMember -> say(Level.WARNING, "Proposes to remove ${who(action.oldMember, me)} from the multisig.")
        is ConfigAction.ChangeThreshold -> say(Level.WARNING, "Proposes to change the votes needed to ${action.newThreshold}.")
        is ConfigAction.SetTimeLock ->
            if (action.newTimeLock == 0u) say(Level.WARNING, "Proposes to remove the time lock: approved proposals would run at once.")
            else say(Level.INFO, "Proposes a time lock of ${action.newTimeLock} s.")
        is ConfigAction.AddSpendingLimit -> say(
            Level.WARNING,
            "Proposes to let ${action.members.size} member(s) spend up to ${action.amount} base units of ${short(action.mint)} " +
                "per ${action.period.name.lowercase()} without a vote${if (action.destinations.isEmpty()) ", to any address" else ""}.",
        )
        is ConfigAction.RemoveSpendingLimit -> say(Level.INFO, "Proposes to remove a spending limit.")
        is ConfigAction.SetRentCollector -> say(Level.INFO, "Proposes to send the multisig's rent to ${action.newRentCollector?.let(::short) ?: "nobody"}.")
    }

    /** A change made by the config authority alone: no vote. */
    private fun direct(authority: SolanaPublicKey, what: String, say: (Level, String) -> Unit) =
        say(Level.DANGER, "Changes the multisig WITHOUT a vote, through its config authority ${short(authority)}: $what.")

    private fun member(m: Member) = "${short(m.key)} (${permissions(m.permissions.mask)})"
    private fun who(key: SolanaPublicKey, me: SolanaPublicKey) = if (key.same(me)) "YOU" else short(key)

    private fun permissions(mask: UByte): String = listOfNotNull(
        "propose".takeIf { mask.toInt() and 1 != 0 },
        "vote".takeIf { mask.toInt() and 2 != 0 },
        "execute".takeIf { mask.toInt() and 4 != 0 },
    ).joinToString(" + ").ifEmpty { "no permissions" }

    // ---------- System program (SOL), read by hand: two instructions matter here ----------
    private fun system(ix: TransactionInstruction, me: SolanaPublicKey, say: (Level, String) -> Unit) {
        val d = ix.data
        if (d.size < 4) return say(Level.WARNING, "A system instruction this app cannot read.")
        when (u32(d, 0)) {
            2L -> if (d.size == 12 && ix.accounts.size >= 2) {
                val from = ix.accounts[0].publicKey
                val amount = "${sol(u64(d, 4))} SOL"
                if (from.same(me)) say(Level.INFO, "Sends $amount to ${short(ix.accounts[1].publicKey)}.")
                else say(Level.INFO, "Moves $amount from ${short(from)} to ${short(ix.accounts[1].publicKey)}.")
            } else say(Level.WARNING, "A malformed SOL transfer.")
            1L -> if (d.size == 36 && ix.accounts.isNotEmpty()) {
                val owner = SolanaPublicKey(d.copyOfRange(4, 36))
                val account = ix.accounts[0].publicKey
                if (account.same(me)) say(Level.DANGER, "Hands your account to the program ${short(owner)}: it could take everything in it.")
                else say(Level.WARNING, "Gives the account ${short(account)} to the program ${short(owner)}.")
            } else say(Level.WARNING, "A malformed account assignment.")
            else -> Unit // creating accounts, nonces: nothing given away
        }
    }

    // ---------- formatting ----------
    private fun SolanaPublicKey.same(other: SolanaPublicKey) = bytes.contentEquals(other.bytes)
    private fun short(k: SolanaPublicKey) =
        if (k.same(VaultMessage.FROM_LOOKUP_TABLE)) "(an address from a lookup table)" else k.base58().let { "${it.take(4)}…${it.takeLast(4)}" }
    private fun units(amount: ULong, decimals: Int?): String =
        if (decimals == null) "$amount base units"
        else BigDecimal(amount.toString()).movePointLeft(decimals).stripTrailingZeros().toPlainString()
    private fun sol(lamports: Long) = BigDecimal(lamports.toULong().toString()).movePointLeft(9).stripTrailingZeros().toPlainString()
    private fun u32(d: ByteArray, o: Int) = (0 until 4).fold(0L) { acc, k -> acc or ((d[o + k].toLong() and 0xFF) shl (8 * k)) }
    private fun u64(d: ByteArray, o: Int) = (0 until 8).fold(0L) { acc, k -> acc or ((d[o + k].toLong() and 0xFF) shl (8 * k)) }
}
