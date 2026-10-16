package de.norm.events.scraper.ritterbutzke

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [RitterButzkeDetailPageScraper].
 *
 * Parses static snapshots of Ritter Butzke `/event/DDMMYY-<Name>` pages for deterministic,
 * offline-safe testing without HTTP fetching. The snapshots predate the pages' JSON-LD, so their
 * lineups come from the rendered rows.
 */
class RitterButzkeDetailPageScraperTest {
    private val scraper = RitterButzkeDetailPageScraper(lineupFromJsonLd = true)

    private fun scrape(
        fixture: String,
        slug: String
    ): ScrapedEvent {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/ritterbutzke/ritterbutzke-detail-$fixture.html")!!
                .bufferedReader()
                .readText()
        val sourceUrl = "https://club.ritterbutzke.com/event/$slug"
        return scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)!!
    }

    @Test
    fun `maps a fully populated detail page`() {
        val unison = scrape("unison", "070826-Unisonw-ZappedRecords-NizarSarakbi-JosefinaTapia")
        unison.title shouldBe "Unison w/ Zapped Records, Nizar Sarakbi, Josefina Tapia"
        unison.eventType shouldBe EventType.PARTY.name
        unison.eventDate shouldBe LocalDate.of(2026, 8, 7)
        unison.startTime shouldBe LocalTime.of(22, 0)
        unison.doorsTime.shouldBeNull()
        unison.sourceId shouldBe "ritter_butzke:070826-Unisonw-ZappedRecords-NizarSarakbi-JosefinaTapia"
        unison.imageUrl!! shouldStartWith "https://club.ritterbutzke.com/img/"
    }

    @Test
    fun `reads the header only once despite the duplicated breakpoint blocks`() {
        // The whole header is rendered twice, for the mobile and desktop breakpoints.
        val unison = scrape("unison", "070826-Unisonw-ZappedRecords-NizarSarakbi-JosefinaTapia")
        unison.title shouldBe "Unison w/ Zapped Records, Nizar Sarakbi, Josefina Tapia"
        unison.eventDate shouldBe LocalDate.of(2026, 8, 7)
    }

    @Test
    fun `reads the rendered date rather than the stale date in the slug`() {
        val deeportament = scrape("deeportament", "310726-DeeportamentCommunityw-NicoMorano-OpenAir-Indoor")
        deeportament.eventDate shouldBe LocalDate.of(2026, 9, 4)
        deeportament.startTime shouldBe LocalTime.of(18, 0)
    }

    @Test
    fun `falls back to the rendered DJ rows without JSON-LD, and never stores the title`() {
        val unison = scrape("unison", "070826-Unisonw-ZappedRecords-NizarSarakbi-JosefinaTapia")
        unison.artists.map { it.role }.toSet() shouldBe setOf("DJ")
        unison.artists.map { it.name } shouldBe
            listOf("JOSEFINA TAPIA", "NIZAR SARAKBI", "PAUL K", "CYKO", "SCOOPSI", "TRC", "VICTOR VONE", "ZAHARA", "HANAFIA")
    }

    @Test
    fun `reads a single-act lineup`() {
        scrape("extrawelt", "100427-WeltauswahlbyExtrawelt").artists.map { it.name } shouldBe listOf("EXTRAWELT (live)")
    }

    @Test
    fun `prefers the JSON-LD performers over the rendered rows and their notes`() {
        val sourceUrl = "https://club.ritterbutzke.com/event/281126-Lexy-K-Paul-live"
        val html =
            """
            <html><head><script type="application/ld+json">
            [{"@type": "MusicEvent", "name": "Lexy & K-Paul", "performer": [{"@type": "MusicGroup", "name": "LEXY &amp; K-PAUL"}]}]
            </script></head><body><h1>Lexy &amp; K-Paul</h1>
            <div><p>Line Up:</p><div style="padding-left: 1rem"><a href="#">LEXY &amp; K-PAUL live</a></div></div>
            </body></html>
            """.trimIndent()
        scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)!!.artists.map { it.name } shouldBe listOf("LEXY & K-PAUL")
    }

    @Test
    fun `reads the pretix widget's shop url`() {
        // The widget renders client-side; its `event` attribute is the only server-rendered copy.
        val unison = scrape("unison", "070826-Unisonw-ZappedRecords-NizarSarakbi-JosefinaTapia")
        unison.ticketUrl!! shouldStartWith "https://tickets.ritterbutzke.com/"
    }

    @Test
    fun `falls back to the Resident Advisor button when there is no pretix widget`() {
        val deeportament = scrape("deeportament", "310726-DeeportamentCommunityw-NicoMorano-OpenAir-Indoor")
        deeportament.ticketUrl!! shouldStartWith "https://de.ra.co/events/"
        deeportament.artists shouldHaveSize 7
    }

    @Test
    fun `publishes no price`() {
        val unison = scrape("unison", "070826-Unisonw-ZappedRecords-NizarSarakbi-JosefinaTapia")
        unison.pricePresale.shouldBeNull()
        unison.priceBoxOffice.shouldBeNull()
        unison.soldOut shouldBe false
    }

    @Test
    fun `leaves the description out, because the first block can be the refund terms`() {
        scrape("extrawelt", "100427-WeltauswahlbyExtrawelt").description.shouldBeNull()
    }

    @Test
    fun `returns null for a page without a title`() {
        val sourceUrl = "https://club.ritterbutzke.com/event/070826-Unison"
        val document = Jsoup.parse("<html><body><main></main></body></html>", sourceUrl)
        scraper.scrape(document, sourceUrl).shouldBeNull()
    }
}
