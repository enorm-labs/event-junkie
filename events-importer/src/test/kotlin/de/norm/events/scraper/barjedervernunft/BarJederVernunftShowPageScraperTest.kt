package de.norm.events.scraper.barjedervernunft

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Unit tests for [BarJederVernunftShowPageScraper] and [BarJederVernunftShow.applyTo],
 * parsing saved snapshots of two `/de/programm/programmuebersicht/<show>.html` pages.
 *
 * The two fixtures differ in the shape of the `Überblick` grid: the residency renders four
 * `col-lg-3` cells (including the optional `Programmhinweis`), the guest show three
 * `col-lg-4` cells — which is why values are looked up by label rather than by position.
 */
class BarJederVernunftShowPageScraperTest {
    private val scraper = BarJederVernunftShowPageScraper()

    private val ohWhatANight by lazy { scrapeFixture("barjedervernunft-show-oh-what-a-night.html") }
    private val happyDisharmonists by lazy { scrapeFixture("barjedervernunft-show-happy-disharmonists.html") }

    @Test
    fun `scrape reads genre, price range and full description from a four-cell overview grid`() {
        val show = ohWhatANight.shouldNotBeNull()

        show.genre shouldBe "Musik-Show"
        show.priceNote shouldBe "Ab 19,90 € bis 49,90 €"
        show.pricePresale shouldBe BigDecimal("19.90")
        show.description.shouldNotBeNull() shouldContain "Feelgood-Music at its best"
    }

    @Test
    fun `scrape reads the same fields from a three-cell overview grid`() {
        val show = happyDisharmonists.shouldNotBeNull()

        show.genre shouldBe "A cappella"
        show.priceNote shouldBe "Ab 12,90 € bis 27,90 €"
        show.pricePresale shouldBe BigDecimal("12.90")
        show.description.shouldNotBeNull() shouldContain "einer der schrägsten und ausgefallensten Chöre"
    }

    @Test
    fun `scrape keeps only the regular admission range, not the reduced-price line`() {
        val show = ohWhatANight.shouldNotBeNull()

        // The Preise cell stacks "Ab … bis …" and "Ermäßigt ab … bis …" as <br>-separated lines.
        show.priceNote.shouldNotBeNull() shouldNotContain "Ermäßigt"
        show.pricePresale shouldBe BigDecimal("19.90")
    }

    @Test
    fun `scrape excludes the cast list and the pull quote from the description`() {
        val description = ohWhatANight.shouldNotBeNull().description.shouldNotBeNull()

        // "Mitwirkende" (cast) and "Kreativteam" live in a later section.
        description shouldNotContain "Kreativteam"
        // The press quote is rendered in a card alongside the blurb, not in its text block.
        description shouldNotContain "Wer hier nicht ausflippt vor Glück"
    }

    @Test
    fun `scrape returns null for a page carrying no show fields`() {
        val document = Jsoup.parse("<html><body><main><h1>Seite nicht gefunden</h1></main></body></html>", SHOW_URL)

        scraper.scrape(document).shouldBeNull()
    }

    @Test
    fun `applyTo types a music genre as a concert and bills the performer as headliner`() {
        val show = happyDisharmonists.shouldNotBeNull()

        val enriched = show.applyTo(occurrence(title = "The Happy Disharmonists"))

        enriched.eventType shouldBe "CONCERT"
        enriched.genre shouldBe "A cappella"
        enriched.artists shouldHaveSize 1
        enriched.artists.single().name shouldBe "The Happy Disharmonists"
        enriched.artists.single().role shouldBe "HEADLINER"
    }

    @Test
    fun `applyTo types a staged format as a show and mints no artist from the production name`() {
        val show = ohWhatANight.shouldNotBeNull()

        val enriched = show.applyTo(occurrence(title = "Oh What A Night!"))

        enriched.eventType shouldBe "SHOW"
        enriched.genre shouldBe "Musik-Show"
        enriched.artists shouldHaveSize 0
    }

    @Test
    fun `applyTo defaults an unknown genre to SHOW rather than inventing an artist`() {
        val show = BarJederVernunftShow(genre = "Puppenspiel", pricePresale = null, priceNote = null, description = null)

        val enriched = show.applyTo(occurrence(title = "Ein Abend"))

        enriched.eventType shouldBe "SHOW"
        enriched.genre shouldBe "Puppenspiel"
        enriched.artists shouldHaveSize 0
    }

    @Test
    fun `applyTo overwrites the calendar's truncated teaser with the full description`() {
        val show = ohWhatANight.shouldNotBeNull()

        val enriched = show.applyTo(occurrence(title = "Oh What A Night!", description = "Oh What A Night: Ein musikalischer..."))

        enriched.description.shouldNotBeNull() shouldContain "Feelgood-Music at its best"
    }

    @Test
    fun `applyTo keeps the calendar teaser when the show page has no description`() {
        val show = BarJederVernunftShow(genre = "Chanson", pricePresale = null, priceNote = null, description = null)

        val enriched = show.applyTo(occurrence(title = "Tim Fischer", description = "Ein Chansonabend..."))

        enriched.description shouldBe "Ein Chansonabend..."
    }

    private fun scrapeFixture(fixture: String): BarJederVernunftShow? {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/barjedervernunft/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, SHOW_URL))
    }

    /** A calendar occurrence as the overview scraper produces it, before show-page enrichment. */
    private fun occurrence(
        title: String,
        description: String? = null
    ) = ScrapedEvent(
        title = title,
        subtitle = "A sub-line",
        description = description,
        eventDate = LocalDate.of(2026, 8, 24),
        sourceUrl = SHOW_URL,
        sourceId = "bar_jeder_vernunft:2026-08-24-a-show"
    )

    @Test
    fun `a comedy genre is comedy, and Kabarett stays a show`() {
        resolveEventType("Musik-Comedy") shouldBe "COMEDY"
        resolveEventType("Comedy") shouldBe "COMEDY"
        resolveEventType("Musik-Kabarett") shouldBe "SHOW"
        resolveEventType("Kabarett") shouldBe "SHOW"
    }

    private companion object {
        const val SHOW_URL = "https://www.bar-jeder-vernunft.de/de/programm/programmuebersicht/a-show.html"
    }
}
