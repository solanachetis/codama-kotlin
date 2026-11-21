package core

import codama.generated.token.TokenProgram
import codama.generated.token.accounts.Mint
import codama.generated.token.accounts.Token
import codama.generated.token.instructions.approveInstruction
import codama.generated.token.instructions.initializeAccount3Instruction
import codama.generated.token.instructions.initializeMint2Instruction
import codama.generated.token.instructions.mintToInstruction
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * For testing on devnet: one transaction, signed by the wallet alone, that creates a test token, credits the wallet
 * with it, and gives an unlimited spending permission to a random address. The new accounts are derived from the
 * wallet with a seed, so no extra key has to sign.
 */
object DevnetTestApproval {
    private val SYSTEM = SolanaPublicKey.from("11111111111111111111111111111111")

    /** Solana's `create_with_seed`: sha256(base ‖ seed ‖ program). */
    fun addressWithSeed(base: SolanaPublicKey, seed: String, program: SolanaPublicKey): SolanaPublicKey {
        val seedBytes = seed.toByteArray()
        require(seedBytes.size <= 32) { "seed too long" }
        return SolanaPublicKey(MessageDigest.getInstance("SHA-256").digest(base.bytes + seedBytes + program.bytes))
    }

    private fun createAccountWithSeed(owner: SolanaPublicKey, created: SolanaPublicKey, seed: String, lamports: Long, space: Long): TransactionInstruction {
        val out = ByteArrayOutputStream()
        fun u64(v: Long) = out.write(ByteArray(8) { (v shr (8 * it)).toByte() })
        out.write(byteArrayOf(3, 0, 0, 0))
        out.write(owner.bytes)
        val seedBytes = seed.toByteArray()
        u64(seedBytes.size.toLong())
        out.write(seedBytes)
        u64(lamports)
        u64(space)
        out.write(TokenProgram.PROGRAM_ID.bytes)
        // The base is the funding account here, so it is not repeated.
        return TransactionInstruction(SYSTEM, listOf(AccountMeta(owner, true, true), AccountMeta(created, false, true)), out.toByteArray())
    }

    fun instructions(owner: SolanaPublicKey, rentMint: Long, rentAccount: Long, suffix: String, delegate: SolanaPublicKey): List<TransactionInstruction> {
        val mintSeed = "swg-mint-$suffix"
        val accountSeed = "swg-acct-$suffix"
        val mint = addressWithSeed(owner, mintSeed, TokenProgram.PROGRAM_ID)
        val account = addressWithSeed(owner, accountSeed, TokenProgram.PROGRAM_ID)
        return listOf(
            createAccountWithSeed(owner, mint, mintSeed, rentMint, Mint.SIZE.toLong()),
            initializeMint2Instruction(mint, decimals = 6u, mintAuthority = owner, freezeAuthority = null),
            createAccountWithSeed(owner, account, accountSeed, rentAccount, Token.SIZE.toLong()),
            initializeAccount3Instruction(account, mint, owner),
            mintToInstruction(mint, account, owner, 1_000_000_000uL),
            approveInstruction(account, delegate, owner, ULong.MAX_VALUE),
        )
    }

    /** The unsigned transaction, with a fresh random seed suffix and delegate. */
    fun transaction(rpc: SolanaRpc, owner: SolanaPublicKey): ByteArray {
        val random = SecureRandom()
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val suffix = String(CharArray(8) { alphabet[random.nextInt(alphabet.length)] })
        val delegate = SolanaPublicKey(ByteArray(32).also(random::nextBytes))
        val ixs = instructions(
            owner,
            rentMint = rpc.minimumBalanceForRentExemption(Mint.SIZE),
            rentAccount = rpc.minimumBalanceForRentExemption(Token.SIZE),
            suffix = suffix,
            delegate = delegate,
        )
        return LegacyTransaction.compile(owner, rpc.latestBlockhash(), ixs)
    }
}
