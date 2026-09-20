package de.norm.events.scraper.tresor

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Unit tests for [TresorOverviewPageScraper].
 *
 * Uses a real `/club/events/` snapshot. The listing already groups each night's DJs by floor in the
 * markup, so most of these tests pin the two places judgement was still needed: reducing a branded
 * floor label to the room it names, and dropping the venue's `???` placeholder.
 */
class TresorOverviewPageScraperTest {
    private val scraper = TresorOverviewPageScraper()
    private val baseUrl = "https://tresorberlin.com/club/events/"
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/tresor/tresor-events.html")!!
                .bufferedReader()
                .readText()
        events = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(slug: String): ScrapedEvent = events.first { it.sourceId == "tresor:$slug" }

    @Test
    fun `extracts every event item`() {
        events shouldHaveSize 30
        events.map { it.sourceId }.distinct() shouldHaveSize 30
    }

    @Test
    fun `takes the date from the permalink, since the card prints no year`() {
        val klubnacht = event("20260801-tresor-klubnacht")
        klubnacht.title shouldBe "Tresor Klubnacht"
        klubnacht.eventType shouldBe "PARTY"
        klubnacht.eventDate shouldBe LocalDate.of(2026, 8, 1)
        klubnacht.sourceUrl shouldBe "https://tresorberlin.com/event/20260801-tresor-klubnacht/"
        events.none { it.eventDate == LocalDate.MIN } shouldBe true
    }

    @Test
    fun `groups the DJs by the floor the markup already assigns them`() {
        event("20260801-tresor-klubnacht").artists shouldContainExactly
            listOf(
                ScrapedArtist("Hypnotic Black Magic", "DJ", "Tresor"),
                ScrapedArtist("Developer", "DJ", "Tresor"),
                ScrapedArtist("Maedon", "DJ", "Tresor"),
                ScrapedArtist("Janina", "DJ", "Globus"),
                ScrapedArtist("Malika", "DJ", "Globus"),
                ScrapedArtist("Francesco Farfa", "DJ", "Globus")
            )
    }

    @Test
    fun `drops the venue's unannounced-slot placeholder`() {
        // The Klubnacht bills one slot as "???" between Hypnotic Black Magic and Developer.
        events.flatMap { it.artists }.none { it.name.contains('?') } shouldBe true
    }

    @Test
    fun `reduces a branded floor label to the room it names`() {
        // The venue writes labels like "Globus x Black Rave Culture" and
        // "Tresor New Faces hosted by Grab The Groove / 23h", which would otherwise make a new
        // stage per event.
        events.flatMap { it.artists }.mapNotNull { it.stage }.distinct() shouldContainExactly
            listOf("Tresor", "Globus", "Aurora Bar")
    }

    @Test
    fun `bills every act as a DJ`() {
        events.flatMap { it.artists }.all { it.role == "DJ" } shouldBe true
        // 152 billed on the floors, plus the host of the one New Faces night with no line-up (#339).
        events.sumOf { it.artists.size } shouldBe 153
        events.single { it.title == "Tresor New Faces hosted by Secret Keywords" }.artists.map { it.name } shouldBe listOf("Secret Keywords")
    }

    @Test
    fun `splits a back-to-back slot into both DJs`() {
        val acts = events.flatMap { it.artists }.map { it.name }
        acts shouldContain "pschukk"
        acts shouldContain "Robert We"
        acts.none { it.contains("b2b", ignoreCase = true) } shouldBe true
    }

    @Test
    fun `strips the set-format note so one DJ is one artist across nights`() {
        val acts = events.flatMap { it.artists }.map { it.name }
        acts shouldContain "Ngly"
        acts shouldContain "The Ghost"
        // The venue writes the same marker bare as well.
        acts shouldContain "Shackleton"
        acts shouldContain "dreamcastmoe"
        acts.none { it.contains('[') } shouldBe true
    }

    @Test
    fun `drops the host credit for the collective curating a floor`() {
        // "hosted by HARD WAX" is billed among the DJs but names the host, not a performer.
        events.flatMap { it.artists }.none { it.name.contains("hosted by", ignoreCase = true) } shouldBe true
    }

    @Test
    fun `never bills one act twice on the same night`() {
        events.forEach { e -> e.artists.map { it.name.lowercase() }.distinct() shouldHaveSize e.artists.size }
    }

    @Test
    fun `leaves a night without a published lineup empty rather than guessing`() {
        event("20260803-singularity").artists.shouldBeEmpty()
    }

    @Test
    fun `publishes no times, prices or images on the listing`() {
        events.all { it.startTime == null && it.pricePresale == null && it.imageUrl == null } shouldBe true
    }

    @Test
    fun `returns no events for a page without a listing`() {
        scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
