import codama.generated.token.instructions.approveInstruction
import codama.generated.token.instructions.setAuthorityInstruction
import codama.generated.token.types.AuthorityType
import com.solana.publickey.SolanaPublicKey
import safety.GuardTexts
import safety.Level
import safety.TransactionGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuardTextsTest {
    private fun key(i: Int) = SolanaPublicKey(ByteArray(32) { i.toByte() })
    private val me = key(1)

    @Test
    fun frenchHasEveryEnglishKey() = assertEquals(GuardTexts.EN.keys, GuardTexts.FR.keys)

    @Test
    fun noPlaceholderLeftInAnyTemplate() {
        for (texts in listOf(GuardTexts.EN, GuardTexts.FR)) for (k in texts.keys) {
            val s = texts.t(k, "a", "b", "c", "d", "e")
            assertFalse(Regex("\\{\\d}").containsMatchIn(s), "$k: $s")
        }
    }

    @Test
    fun frenchAlertsSameLevelsDifferentWords() {
        val ix = approveInstruction(key(2), key(66), me, amount = ULong.MAX_VALUE)
        val en = TransactionGuard.check(listOf(ix), me)
        val fr = TransactionGuard.check(listOf(ix), me, GuardTexts.FR)
        assertEquals(en.map { it.level }, fr.map { it.level })
        assertTrue(fr.single().message.contains("TOUS les jetons"), fr.single().message)
        val owner = setAuthorityInstruction(key(2), me, AuthorityType.ACCOUNT_OWNER, key(66))
        assertTrue(TransactionGuard.check(listOf(owner), me, GuardTexts.FR).single().message.contains("propriétaire"))
    }

    @Test
    fun forLangPicksFrenchOrFallsBackToEnglish() {
        assertEquals("fr", GuardTexts.forLang("fr").lang)
        assertEquals("en", GuardTexts.forLang("de").lang)
    }

    @Test
    fun nearMaxApprovalIsUnlimited() {
        val alerts = TransactionGuard.check(listOf(approveInstruction(key(2), key(66), me, amount = TransactionGuard.UNLIMITED)), me)
        assertEquals(Level.DANGER, alerts.single().level)
        val below = TransactionGuard.check(listOf(approveInstruction(key(2), key(66), me, amount = TransactionGuard.UNLIMITED - 1uL)), me)
        assertEquals(Level.WARNING, below.single().level)
    }

    @Test
    fun neverSaysSafe() {
        val banned = Regex("\\b(safe|no risk|sûr|sans risque|aucun risque)\\b", RegexOption.IGNORE_CASE)
        for (texts in listOf(GuardTexts.EN, GuardTexts.FR)) for (k in texts.keys) {
            assertFalse(banned.containsMatchIn(texts.t(k, "x", "x", "x", "x", "x")), k)
        }
    }
}
