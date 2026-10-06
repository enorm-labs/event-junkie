package de.norm.events.scraper.delphi

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.alternateLanguageUrl
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Unit tests for [DelphiProductionPageScraper].
 *
 * Parses static snapshots of Theater im Delphi `/programm/?prod=<id>` pages for deterministic,
 * offline-safe testing without HTTP fetching.
 */
class DelphiProductionPageScraperTest {
    private val scraper = DelphiProductionPageScraper()

    private fun scrape(productionId: String): DelphiProduction {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/delphi/delphi-production-$productionId.html")!!
                .bufferedReader()
                .readText()
        val sourceUrl = "https://theater-im-delphi.de/programm/?prod=$productionId"
        return scraper.scrape(Jsoup.parse(html, sourceUrl))!!
    }

    @Test
    fun `reads the German blurb, the link to the English page and the English blurb`() {
        val html = { language: String ->
            javaClass.classLoader
                .getResourceAsStream("scraper/delphi/delphi-production-532-$language.html")!!
                .bufferedReader()
                .readText()
        }
        val german = Jsoup.parse(html("de"), "https://theater-im-delphi.de/programm/?prod=532")

        german.alternateLanguageUrl("en") shouldBe "https://theater-im-delphi.de/en/programm/?prod=532"
        scraper.scrape(german)!!.description!! shouldStartWith "Frauen sollen resilient"
        val english = Jsoup.parse(html("en"), "https://theater-im-delphi.de/en/programm/?prod=532")
        scraper.scrape(english)!!.description!! shouldStartWith "Should women be expected"
    }

    @Test
    fun `reads the full blurb and the full-width photo`() {
        val swanLake = scrape("488")
        swanLake.description!! shouldContain "Live-Streichquartett"
        swanLake.imageUrl!! shouldStartWith "https://theater-im-delphi.de/wp-content/uploads/programm/"
    }

    @Test
    fun `reads a production the programme row left unlabelled`() {
        // Jake Xerxes Fussell carries no category anywhere, but still has its blurb and photo.
        val fussell = scrape("519")
        fussell.description!!.isNotBlank() shouldBe true
        fussell.imageUrl!!.isNotBlank() shouldBe true
    }

    @Test
    fun `supersedes the programme row's teaser and thumbnail`() {
        val event =
            ScrapedEvent(
                title = "Schwanensee – Jenseits der Bühne",
                description = "A one-sentence teaser.",
                eventDate = LocalDate.of(2026, 9, 27),
                imageUrl = "https://theater-im-delphi.de/thumb.jpg",
                sourceUrl = "https://theater-im-delphi.de/programm/?prod=488",
                sourceId = "theater_im_delphi:488/2026-09-27-15:00"
            )
        val enriched = scrape("488").applyTo(event)

        enriched.description!! shouldContain "Live-Streichquartett"
        enriched.imageUrl!! shouldContain "wp-content/uploads/programm/"
        // Everything the production page does not state is left alone.
        enriched.title shouldBe event.title
        enriched.eventDate shouldBe event.eventDate
        enriched.sourceId shouldBe event.sourceId
    }

    @Test
    fun `keeps the row's own data where the production page states nothing`() {
        val event =
            ScrapedEvent(
                title = "Delphis Orakel x Makoto Sakamoto",
                description = "A one-sentence teaser.",
                eventDate = LocalDate.of(2026, 8, 9),
                imageUrl = "https://theater-im-delphi.de/thumb.jpg",
                sourceUrl = "https://theater-im-delphi.de/programm/?prod=528",
                sourceId = "theater_im_delphi:528/2026-08-09-18:00"
            )
        val enriched = DelphiProduction(description = null, imageUrl = null).applyTo(event)

        enriched.description shouldBe "A one-sentence teaser."
        enriched.imageUrl shouldBe "https://theater-im-delphi.de/thumb.jpg"
    }

    @Test
    fun `returns null for a page without a production heading`() {
        val document = Jsoup.parse("<html><body><section class='events_section'></section></body></html>", "https://theater-im-delphi.de/programm/")
        scraper.scrape(document).shouldBeNull()
    }

    @Test
    fun `states nothing the programme page already owns`() {
        // The production page lists the whole run; its dates and prices are not read from here.
        val production = scrape("488")
        production.description!!.isNotBlank() shouldBe true
        DelphiProduction(description = null, imageUrl = null).description.shouldBeNull()
    }
}
