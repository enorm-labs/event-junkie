package de.norm.events.translation

import de.norm.events.event.DescriptionLanguage.ENGLISH
import de.norm.events.event.DescriptionLanguage.GERMAN
import de.norm.events.event.DescriptionLanguage.UNDETERMINED
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class QuotePairsTest {
    @Test
    fun `closes a German low-nine quote with a high-six one`() {
        QuotePairs.repair("Ihr Name bedeutet „Der Staat\".", GERMAN) shouldBe "Ihr Name bedeutet „Der Staat“."
    }

    @Test
    fun `turns the pair into English quotes in an English text`() {
        QuotePairs.repair("They call it „trippy pop\".", ENGLISH) shouldBe "They call it “trippy pop”."
    }

    @Test
    fun `repairs every pair in a text`() {
        QuotePairs.repair("Alberto „Beto\" Montenegro und Antonio „Tony\" Casas", GERMAN) shouldBe
            "Alberto „Beto“ Montenegro und Antonio „Tony“ Casas"
    }

    @Test
    fun `leaves a text without a low-nine quote alone`() {
        QuotePairs.repair("Sein Name: \"Der Staat\".", GERMAN) shouldBe "Sein Name: \"Der Staat\"."
    }

    @Test
    fun `leaves a correct pair alone`() {
        QuotePairs.repair("„Der Staat“ und \"mehr\"", GERMAN) shouldBe "„Der Staat“ und \"mehr\""
    }

    @Test
    fun `leaves an unclosed low-nine quote alone`() {
        QuotePairs.repair("„Der Staat ist da.", GERMAN) shouldBe "„Der Staat ist da."
    }

    @Test
    fun `leaves an undetermined language alone`() {
        QuotePairs.repair("„Der Staat\"", UNDETERMINED) shouldBe "„Der Staat\""
    }
}
