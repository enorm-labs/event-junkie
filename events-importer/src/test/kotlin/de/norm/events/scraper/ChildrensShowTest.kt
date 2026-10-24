package de.norm.events.scraper

import de.norm.events.event.EventFeature
import de.norm.events.event.EventFeatureMatch
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ChildrensShowTest {
    @Test
    fun `names the children's shows the venues bill, from the live programme of 2026-10-07`() {
        listOf(
            "KINDERPHILHARMONIE - Klassik für Kleine (0-18 Monate)" to "Großes Bohai im Herbst!",
            "Kinder-Halloween im PETER EDEL" to null,
            "Klavierfest Berlin-Weißensee 2027 - Familienkonzert" to null,
            "Linus Faber – Eine magische Elfenmission" to "Die große Familien-Zaubershow für Kinder ab 4 Jahre",
            "ROLLER KIDZ" to "Rollschuhdisko für Kinder"
        ).map { (title, subtitle) -> childrensShowPhrase(title, subtitle) } shouldBe
            listOf("KINDERPHILHARMONIE", "Kinder-Halloween", "Familienkonzert", "Familien-Zaubershow", "KIDZ")
    }

    @Test
    fun `leaves a band named after children and a show for teenagers unmarked`() {
        listOf(
            "Muttis Kinder" to null,
            "Kinderzimmer Productions" to null,
            "KINDER DER NACHT & DEXIT MANNHEIM – HALLOWEEN RAVE" to null,
            "Lichterkinder" to null,
            "Deine Freunde" to null,
            "Coming-of-Age-Theater" to "ab 13 Jahre",
            "Bridge Markland: Krug in the Box" to "Geeignet für alle Zuschauer*innen ab 12 Jahren"
        ).map { (title, subtitle) -> childrensShowPhrase(title, subtitle) }.distinct() shouldBe listOf(null)
    }

    @Test
    fun `marks an event once, with the venue's phrase`() {
        val event = ScrapedEvent(title = "Kasper", eventDate = LocalDate.of(2026, 11, 1), sourceUrl = "https://example.com", sourceId = "x:1")

        val marked = event.forChildren(" Kindertheater ").forChildrenIf("ab 4 Jahre").forChildrenIf(null)

        marked.features shouldBe listOf(EventFeatureMatch(EventFeature.FOR_CHILDREN, "Kindertheater"))
    }
}
