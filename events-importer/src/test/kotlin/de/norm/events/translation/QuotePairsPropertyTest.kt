package de.norm.events.translation

import de.norm.events.event.DescriptionLanguage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Properties of [QuotePairs.repair] for any text: [QuotePairsTest] pins the examples. */
class QuotePairsPropertyTest {
    private val pieces = Arb.list(Arb.element("„", "\"", "“", "”", "Der Staat", " ", "a", "\n"), 0..20)
    private val texts = pieces.map { it.joinToString("") }
    private val languages = Arb.element(DescriptionLanguage.GERMAN, DescriptionLanguage.ENGLISH)

    @Test
    fun `repairing twice changes nothing more`() =
        runTest {
            checkAll(texts, languages) { text, to ->
                val once = QuotePairs.repair(text, to)
                QuotePairs.repair(once, to) shouldBe once
            }
        }

    @Test
    fun `the length never changes`() =
        runTest {
            checkAll(texts, languages) { text, to ->
                QuotePairs.repair(text, to).length shouldBe text.length
            }
        }

    @Test
    fun `no low-nine quote is left closed by a straight one`() =
        runTest {
            checkAll(texts, languages) { text, to ->
                QuotePairs.repair(text, to) shouldNotContain WRONG_PAIR
            }
        }

    @Test
    fun `a text with no low-nine quote comes back unchanged`() =
        runTest {
            checkAll(Arb.string(), languages) { text, to ->
                val plain = text.replace("„", "")
                QuotePairs.repair(plain, to) shouldBe plain
            }
        }

    private companion object {
        val WRONG_PAIR = Regex("„[^„“”\"]*\"")
    }
}
