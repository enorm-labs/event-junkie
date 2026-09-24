package de.norm.events.translation

import de.norm.events.event.DescriptionLanguage
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The default engine, and the one every environment runs today.
 *
 * It exists so that translating takes two deliberate acts — an engine and a key — rather than
 * happening because a licence column changed (ADR-026).
 */
class NoTranslationEngineTest {
    private val engine = NoTranslationEngine()

    @Test
    @DisplayName("it translates nothing")
    fun `returns null`() =
        runTest {
            engine
                .translate(
                    TranslationRequest(
                        text = "Ein Abend mit Aussicht.",
                        from = DescriptionLanguage.GERMAN,
                        to = DescriptionLanguage.ENGLISH
                    )
                ).shouldBeNull()
        }

    // The id reaches `description_alt_engine`, so it has to say which engine produced a text.
    @Test
    @DisplayName("it names itself")
    fun `names itself`() {
        engine.id shouldBe "none"
    }

    @Test
    @DisplayName("it says it is switched off")
    fun `is not enabled`() {
        engine.enabled shouldBe false
    }
}
