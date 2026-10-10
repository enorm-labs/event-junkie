package de.norm.events.scraper

import de.norm.events.event.EventEntity
import de.norm.events.event.PUBLISHER_ORIGIN
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The split at the import boundary, on the descriptions production stored whole on 2026-10-09 at venues whose scraper
 * does not split (#2986).
 */
class BilingualDescriptionAtImportTest {
    @Test
    fun `ufaFabrik's text splits at its English version heading`() {
        val row = stored("ufafabrik-verbotene-lieben-3000.txt", "ufafabrik")

        val german = row.description.shouldNotBeNull()
        german shouldStartWith "Hinweis: Enthält sexuelle Inhalte"
        german shouldNotContain "English version"
        row.descriptionLanguage shouldBe "de"
        row.descriptionAlt!! shouldStartWith "Verbotene Lieben 3000 is an outlandish comedy"
        row.descriptionAltLanguage shouldBe "en"
        row.descriptionAltOrigin shouldBe PUBLISHER_ORIGIN
    }

    @Test
    fun `Eschschloraque Rümschrümp's text splits at its ENGLISH heading`() {
        val row = stored("eschschloraque-rumschrump-deep-thoughts.txt", "eschschloraque-rumschrump")

        val german = row.description.shouldNotBeNull()
        val english = row.descriptionAlt.shouldNotBeNull()
        german shouldStartWith "Quiz hard and f*ck smarter. Eine Veranstaltung"
        german shouldNotContain "ENGLISH:"
        english shouldStartWith "DEEP THOUGHTS – THE QUEER SEX QUIZ"
        english shouldEndWith "get a little bit smarter about sex."
        row.descriptionAltOrigin shouldBe PUBLISHER_ORIGIN
    }

    // The rule the venue draws above its ENGLISH heading ends neither half (#3062).
    @Test
    fun `Eschschloraque Rümschrümp's German half ends with its text, not the rule above the heading`() {
        val row = stored("eschschloraque-rumschrump-deep-thoughts.txt", "eschschloraque-rumschrump")

        row.description!! shouldEndWith "Hauptsache, ihr seid bereit zum Abquizzen."
        row.descriptionAlt!! shouldStartWith "DEEP THOUGHTS"
    }

    // Badehaus opens the text with a rule of tildes, in both languages, on production on 2026-10-10.
    @Test
    fun `Badehaus's description and second language lose the rule they open with`() {
        val text = fixture("badehaus-call-me-maybe.txt")
        val alt = text.replace("Tickets At The Door", "Tickets An Der Tür")

        val row = scraped(text).toEventEntity(venueId = 1L, venueSlug = "badehaus", eventSourceId = 1L)

        val description = row.description.shouldNotBeNull()
        description shouldStartWith "„Hey, I just met you"
        description shouldEndWith "made with love by Berlin Pop Nights"
        trimEdgeRules(alt) shouldStartWith "„Hey, I just met you"
    }

    // Spanish is neither of the two languages the schema stores (#330).
    @Test
    fun `SO36's Spanish and English text comes back unchanged`() {
        val text = fixture("so36-la-casa-del-perreo.txt")
        val row = scraped(text).toEventEntity(venueId = 1L, venueSlug = "so36", eventSourceId = 1L)

        row.description shouldBe text
        row.descriptionAlt.shouldBeNull()
    }

    @Test
    fun `the venue's own second half replaces a stored machine translation`() {
        val existing = stored("ufafabrik-verbotene-lieben-3000.txt", "ufafabrik").copy(descriptionAlt = "Maschinell", descriptionAltOrigin = "MACHINE")

        val row = scraped(fixture("ufafabrik-verbotene-lieben-3000.txt")).toEventEntity(1L, "ufafabrik", 1L, existing = existing)

        row.descriptionAltOrigin shouldBe PUBLISHER_ORIGIN
        row.descriptionAlt!! shouldStartWith "Verbotene Lieben 3000 is an outlandish comedy"
    }

    private fun stored(
        name: String,
        venueSlug: String
    ): EventEntity = scraped(fixture(name)).toEventEntity(venueId = 1L, venueSlug = venueSlug, eventSourceId = 1L)

    private fun scraped(description: String) =
        ScrapedEvent(
            title = "A night",
            description = description,
            eventDate = LocalDate.of(2026, 11, 7),
            sourceUrl = "https://example.org",
            sourceId = "x:1"
        )

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/bilingual/$name")!!
            .bufferedReader()
            .readText()
            .trimEnd()
}
