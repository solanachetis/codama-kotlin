import codama.generated.squads.instructions.configTransactionCreateInstruction
import codama.generated.squads.instructions.multisigAddMemberInstruction
import codama.generated.squads.instructions.multisigCreateV2Instruction
import codama.generated.squads.instructions.proposalApproveInstruction
import codama.generated.squads.instructions.vaultTransactionCreateInstruction
import codama.generated.squads.types.ConfigAction
import codama.generated.squads.types.Member
import codama.generated.squads.types.Permissions
import codama.generated.squads.types.ProposalVoteArgs
import codama.generated.squads.types.VaultTransactionCreateArgs
import codama.generated.token.instructions.approveCheckedInstruction
import codama.generated.token.instructions.approveInstruction
import codama.generated.token.instructions.burnCheckedInstruction
import codama.generated.token.instructions.closeAccountInstruction
import codama.generated.token.instructions.revokeInstruction
import codama.generated.token.instructions.setAuthorityInstruction
import codama.generated.token.instructions.syncNativeInstruction
import codama.generated.token.instructions.transferCheckedInstruction
import codama.generated.token.types.AuthorityType
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction
import safety.Alert
import safety.Level
import safety.TransactionGuard
import squads.Permission
import squads.VaultMessage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionGuardTest {
    private fun key(i: Int) = SolanaPublicKey(ByteArray(32) { i.toByte() })
    private val me = key(1)
    private val attacker = key(66)
    private val myTokenAccount = key(2)
    private val mint = key(3)
    private val multisig = key(4)
    private val system = SolanaPublicKey.from("11111111111111111111111111111111")

    private fun check(vararg ixs: TransactionInstruction) = TransactionGuard.check(ixs.toList(), me)
    private fun List<Alert>.only(level: Level, containing: String) {
        assertEquals(1, size, "expected one alert, got $this")
        assertEquals(level, first().level, first().message)
        assertTrue(first().message.contains(containing), "\"${first().message}\" should contain \"$containing\"")
    }

    private fun solTransfer(from: SolanaPublicKey, to: SolanaPublicKey, lamports: Long) = TransactionInstruction(
        system, listOf(AccountMeta(from, true, true), AccountMeta(to, false, true)),
        byteArrayOf(2, 0, 0, 0) + ByteArray(8) { (lamports shr (8 * it)).toByte() },
    )
    private fun assign(account: SolanaPublicKey, owner: SolanaPublicKey) =
        TransactionInstruction(system, listOf(AccountMeta(account, true, true)), byteArrayOf(1, 0, 0, 0) + owner.bytes)

    // ---------- what must stay quiet ----------

    @Test
    fun everydayTransactionsAreNotAlarming() {
        check(transferCheckedInstruction(myTokenAccount, mint, key(9), me, amount = 1_500_000uL, decimals = 6u))
            .only(Level.INFO, "Sends 1.5 tokens")
        check(solTransfer(me, key(9), 500_000_000)).only(Level.INFO, "Sends 0.5 SOL")
        check(revokeInstruction(myTokenAccount, me)).only(Level.INFO, "Removes a spending permission")
        check(closeAccountInstruction(myTokenAccount, me, me)).only(Level.INFO, "comes back to you")
        assertEquals(emptyList(), check(syncNativeInstruction(myTokenAccount)), "wrapping SOL gives nothing away")
        val computeBudget = TransactionInstruction(SolanaPublicKey.from("ComputeBudget111111111111111111111111111111"), emptyList(), byteArrayOf(2, 0, 0, 0, 0))
        assertEquals(emptyList(), check(computeBudget))
        assertNull(TransactionGuard.verdict(emptyList()))
    }

    @Test
    fun someoneElsesTokenMovesAreNotYourBusiness() {
        // You only pay the fee: the authority is not you, nothing of yours moves.
        assertEquals(emptyList(), check(transferCheckedInstruction(key(7), mint, key(9), key(8), amount = 5uL, decimals = 0u)))
    }

    // ---------- SPL Token ----------

    @Test
    fun spendingPermissions() {
        check(approveInstruction(myTokenAccount, attacker, me, amount = ULong.MAX_VALUE)).only(Level.DANGER, "ALL the tokens")
        check(approveCheckedInstruction(myTokenAccount, mint, attacker, me, amount = 2_500_000uL, decimals = 6u))
            .only(Level.WARNING, "up to 2.5")
    }

    @Test
    fun authorities() {
        check(setAuthorityInstruction(myTokenAccount, me, AuthorityType.ACCOUNT_OWNER, attacker)).only(Level.DANGER, "they will own every token")
        check(setAuthorityInstruction(myTokenAccount, me, AuthorityType.CLOSE_ACCOUNT, attacker)).only(Level.WARNING, "close your token account")
        check(setAuthorityInstruction(mint, me, AuthorityType.MINT_TOKENS, null)).only(Level.WARNING, "forever")
        check(setAuthorityInstruction(myTokenAccount, me, AuthorityType.ACCOUNT_OWNER, me)).only(Level.INFO, "with you")
    }

    @Test
    fun closingAndBurning() {
        check(closeAccountInstruction(myTokenAccount, attacker, me)).only(Level.WARNING, "sends its SOL to")
        check(burnCheckedInstruction(myTokenAccount, mint, me, amount = 1_000uL, decimals = 3u)).only(Level.WARNING, "Destroys 1 tokens")
    }

    // ---------- System, unknown programs, memos ----------

    @Test
    fun handingTheWalletToAProgram() {
        check(assign(me, attacker)).only(Level.DANGER, "Hands your account to the program")
    }

    @Test
    fun unknownProgramsAreFlagged() {
        val unknown = TransactionInstruction(attacker, listOf(AccountMeta(me, true, true)), byteArrayOf(9))
        check(unknown).only(Level.UNCHECKED, "yours included")
        val memo = TransactionInstruction(SolanaPublicKey.from("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"), emptyList(), "hello".encodeToByteArray())
        check(memo).only(Level.INFO, "\"hello\"")
    }

    // ---------- Squads ----------

    private fun create(configAuthority: SolanaPublicKey?, threshold: UShort, vararg members: SolanaPublicKey) = multisigCreateV2Instruction(
        key(20), key(21), multisig, key(22), me, configAuthority, threshold,
        members.map { Member(it, Permissions(Permission.ALL)) }, timeLock = 0u, rentCollector = null, memo = null,
    )

    @Test
    fun multisigCreation() {
        val safe = check(create(null, 2u, me, key(9), key(10)))
        assertEquals(Level.INFO, TransactionGuard.verdict(safe), safe.toString())
        val hiddenAdmin = check(create(attacker, 2u, me, key(9)))
        assertEquals(Level.DANGER, TransactionGuard.verdict(hiddenAdmin))
        assertTrue(hiddenAdmin.any { it.message.contains("change this multisig alone") })
        assertTrue(check(create(null, 1u, key(9))).any { it.level == Level.WARNING && it.message.contains("not a member") })
        assertTrue(check(create(null, 1u, me, key(9), key(10))).any { it.level == Level.WARNING && it.message.contains("Any single member") })
    }

    @Test
    fun configProposalsAndDirectChanges() {
        val proposal = check(configTransactionCreateInstruction(
            multisig, key(30), me, me,
            actions = listOf(ConfigAction.AddMember(Member(attacker, Permissions(Permission.ALL))), ConfigAction.SetTimeLock(0u), ConfigAction.RemoveMember(me)),
            memo = null,
        ))
        assertEquals(3, proposal.size, proposal.toString())
        assertTrue(proposal.all { it.level == Level.WARNING })
        assertTrue(proposal[2].message.contains("YOU"))
        check(multisigAddMemberInstruction(multisig, attacker, Member(attacker, Permissions(Permission.ALL)), memo = null))
            .only(Level.DANGER, "WITHOUT a vote")
        check(proposalApproveInstruction(multisig, me, key(31), ProposalVoteArgs(memo = null))).only(Level.INFO, "YES")
    }

    @Test
    fun vaultProposalsAreOpened() {
        val vault = key(40)
        val message = VaultMessage.compile(vault, listOf(solTransfer(vault, attacker, 7_000_000_000), assign(vault, attacker)))
        val alerts = check(vaultTransactionCreateInstruction(
            multisig, key(41), me, me,
            VaultTransactionCreateArgs(vaultIndex = 0u, ephemeralSigners = 0u, transactionMessage = message.encode(), memo = null),
        ))
        assertEquals(2, alerts.size, alerts.toString())
        assertTrue(alerts.all { it.message.startsWith("If approved, the vault: ") })
        assertTrue(alerts[0].message.contains("Sends 7 SOL"))
        assertEquals(Level.DANGER, alerts[1].level)
        val unreadable = check(vaultTransactionCreateInstruction(
            multisig, key(41), me, me,
            VaultTransactionCreateArgs(vaultIndex = 0u, ephemeralSigners = 0u, transactionMessage = byteArrayOf(1, 2, 3), memo = null),
        ))
        unreadable.only(Level.WARNING, "cannot open")
    }

    /** Proposals that load accounts from an address lookup table (big swaps do) are opened too. */
    @Test
    fun vaultProposalsWithLookupTables() {
        val vault = key(40)
        val transfer = byteArrayOf(2, 0, 0, 0) + ByteArray(8) { (1_000_000_000L shr (8 * it)).toByte() }
        val bytes = byteArrayOf(1, 1, 0) + // 1 signer (the vault, writable), no writable non-signer
            byteArrayOf(2) + vault.bytes + system.bytes +
            byteArrayOf(1, 1, 2, 0, 2, transfer.size.toByte(), 0) + transfer + // system transfer: vault -> account #2 (from the table)
            byteArrayOf(1) + key(50).bytes + byteArrayOf(1, 5, 0) // one table: one writable account, no read-only one
        val decoded = VaultMessage.decode(bytes)
        assertEquals(VaultMessage.FROM_LOOKUP_TABLE.base58(), decoded.instructions[0].accounts[1].publicKey.base58())
        assertTrue(decoded.instructions[0].accounts[1].isWritable)
        val alerts = check(vaultTransactionCreateInstruction(
            multisig, key(41), me, me,
            VaultTransactionCreateArgs(vaultIndex = 0u, ephemeralSigners = 0u, transactionMessage = bytes, memo = null),
        ))
        alerts.only(Level.INFO, "Sends 1 SOL to (an address from a lookup table)")
        // Even the program can come from a table (seen on mainnet): it can't be checked, and it says so.
        val programFromTable = byteArrayOf(1, 1, 0) + byteArrayOf(1) + vault.bytes +
            byteArrayOf(1, 1, 1, 0, 1, 0, 9) + // program = account #1, from the table; one account: the vault; data [9]
            byteArrayOf(1) + key(50).bytes + byteArrayOf(0, 1, 3) // one table: one read-only account
        check(vaultTransactionCreateInstruction(
            multisig, key(41), me, me,
            VaultTransactionCreateArgs(vaultIndex = 0u, ephemeralSigners = 0u, transactionMessage = programFromTable, memo = null),
        )).only(Level.UNCHECKED, "(an address from a lookup table), which this app cannot check")
    }

    @Test
    fun vaultMessagesDecodeBackToTheSameBytes() {
        val vault = key(40)
        val ixs = listOf(solTransfer(vault, key(9), 1_000), transferCheckedInstruction(key(5), mint, key(6), vault, amount = 3uL, decimals = 0u))
        val bytes = VaultMessage.compile(vault, ixs).encode()
        val decoded = VaultMessage.decode(bytes)
        assertContentEquals(bytes, decoded.encode())
        assertEquals(ixs.size, decoded.instructions.size)
        decoded.instructions.zip(ixs).forEach { (d, o) ->
            assertEquals(o.programId.base58(), d.programId.base58())
            assertContentEquals(o.data, d.data)
            assertEquals(o.accounts.map { it.publicKey.base58() }, d.accounts.map { it.publicKey.base58() })
        }
    }

    // ---------- a real-looking drainer ----------

    /** What wallet-draining sites ask you to sign, hidden behind a "claim your airdrop" button. */
    @Test
    fun aDrainerIsCaught() {
        val alerts = check(
            transferCheckedInstruction(myTokenAccount, mint, key(9), me, amount = 1uL, decimals = 0u), // looks harmless
            approveInstruction(myTokenAccount, attacker, me, amount = ULong.MAX_VALUE),
            setAuthorityInstruction(myTokenAccount, me, AuthorityType.ACCOUNT_OWNER, attacker),
            assign(me, attacker),
        )
        assertEquals(Level.DANGER, TransactionGuard.verdict(alerts))
        assertEquals(listOf(1, 2, 3), alerts.filter { it.level == Level.DANGER }.map { it.instruction })
    }
}
