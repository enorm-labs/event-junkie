package de.norm.events.scraper.ausland

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class AuslandOverviewPageScraperTest {
    private val baseUrl = "https://ausland.berlin/program/all"
    private val events = AuslandOverviewPageScraper().scrape(Jsoup.parse(fixture("ausland-overview.html"), baseUrl), baseUrl)

    private fun bySlug(slug: String): ScrapedEvent = events.single { it.sourceId == "ausland:$slug" }

    @Test
    fun `reads every event on the listing`() {
        events shouldHaveSize 11
    }

    @Test
    fun `reads a concert night with its line-up`() {
        val event = bySlug("lorena-izquierdo-mizuki-ishikawa-matthias-koole-frictive-frequencies")

        event.title shouldBe "Lorena Izquierdo / Mizuki Ishikawa / Matthias Koole / Frictive Frequencies"
        event.eventDate shouldBe LocalDate.of(2026, 11, 7)
        event.startTime shouldBe LocalTime.of(19, 0)
        event.eventType shouldBe EventType.CONCERT.name
        event.typeIsFallback shouldBe true
        event.sourceUrl shouldBe "https://ausland.berlin/event/lorena-izquierdo-mizuki-ishikawa-matthias-koole-frictive-frequencies"
        event.imageUrl shouldBe "https://ausland.berlin/fileadmin/user_upload/Mizuki_FrictiveFrequencies_1080x1080_SQUARE.jpg"
        event.artists.map { it.name } shouldContainExactly listOf("Mizuki Ishikawa", "Matthias Koole", "Lorena Izquierdo")
        event.subtitle.shouldBeNull()
    }

    @Test
    fun `takes the series line as the subtitle and the first slide of a gallery as the image`() {
        val event = bySlug("all-the-rivers-flow-light-trees")

        event.subtitle shouldBe "As part of the series All The Rivers"
        event.imageUrl shouldBe "https://ausland.berlin/fileadmin/user_upload/AllTheRivers/ATR_Flow/Light_Trees_5_c_Miyeon_Choi.JPG"
        event.startTime shouldBe LocalTime.of(20, 0)
        event.artists.map { it.name } shouldContainExactly listOf("Inky Lee", "Paulina Güllü", "Haesoo Eshu Jung", "Hyewon Suk", "Hyemi Jo")
    }

    @Test
    fun `types the house's other formats from the title`() {
        bySlug("all-the-river-return-workshop-in-bewegung").eventType shouldBe EventType.OTHER.name
        bySlug("all-the-rivers-residency-bound-with-the-living").eventType shouldBe EventType.OTHER.name
        bySlug("eclat-crew-music-making-meetup-1").eventType shouldBe EventType.OTHER.name
        bySlug("vinyl-hours-record-club").eventType shouldBe EventType.OTHER.name
        bySlug("sphagnum-russowii-latens-et-repertum-a-sound-art-project-for-a-hidden-moss").eventType shouldBe EventType.EXHIBITION.name
        bySlug("vinyl-hours-record-club").typeIsFallback shouldBe false
    }

    @Test
    fun `keeps an event with no picture or no line-up`() {
        bySlug("all-the-river-return-workshop-in-bewegung").imageUrl.shouldBeNull()
        bySlug("eclat-crew-music-making-meetup-1").artists.shouldBeEmpty()
    }

    @Test
    fun `skips an item without a readable date`() {
        val html = """<section class="event listitem"><a itemprop="url" href="/event/x"><p class="date">soon</p><h2>X</h2></a></section>"""

        AuslandOverviewPageScraper().scrape(Jsoup.parse(html, baseUrl), baseUrl).shouldBeEmpty()
    }

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/ausland/$name")!!
            .bufferedReader()
            .readText()
}
