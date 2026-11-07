package de.norm.events.scraper.giri

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [GiriProgrammePageScraper], against saved copies of two real month pages. */
class GiriProgrammePageScraperTest {
    private val scraper = GiriProgrammePageScraper()
    private val programmeUrl = "https://giri.berlin/programme/"

    private fun scrape(
        fixture: String,
        url: String = programmeUrl
    ): List<ScrapedEvent> {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/giri/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, url), url)
    }

    private fun scrapeHtml(html: String): List<ScrapedEvent> = scraper.scrape(Jsoup.parse(html, programmeUrl), programmeUrl)

    private val november: List<ScrapedEvent> by lazy { scrape("giri-overview.html") }

    private fun event(titlePrefix: String) = november.single { it.title.startsWith(titlePrefix) }

    /** One calendar link and its template, in the markup the site uses since 2026-11. */
    private fun night(
        facts: String,
        lineup: String = "",
        category: String = "music",
        title: String = "Night"
    ) = """
        <a class="calendar-event" data-event="form-x" data-event-date="2026-11-18" data-event-category="$category"
           href="https://giri.berlin/programme/2026-11-18-night--abc/"></a>
        <template data-event-content="form-x"><article class="event-detail"><div>
          <h2>$title</h2>
          <dl class="event-facts">$facts</dl>
          <div class="event-description"><div class="giri-event-description"><p>About the night.</p><h3>Lineup</h3>$lineup</div></div>
        </div></article></template>
        """.trimIndent()

    @Test
    fun `scrape reads every calendar link with its date`() {
        november.map { it.eventDate } shouldContainExactly listOf(18, 25).map { LocalDate.of(2026, 11, it) }
        november.map { it.sourceId } shouldContainExactly
            listOf("giri:form-224b5be43b7afed8f23fe62aafdcd051", "giri:form-5a288c226eaafbb0209bae81edc9977e")
    }

    @Test
    fun `scrape reads a fully populated night from its template`() {
        val night = event("GIRI x SYNESTRANSIA")

        night.title shouldBe "GIRI x SYNESTRANSIA Experiment #5: The Freedom of Five"
        night.sourceUrl shouldBe
            "https://giri.berlin/programme/2026-11-18-giri-x-synestransia-experiment-5-the-freedom-of-five--eb4bc25c42b963c522f44ac7/"
        night.doorsTime shouldBe LocalTime.of(18, 0)
        night.startTime.shouldBeNull()
        night.endTime.shouldBeNull()
        night.endDate.shouldBeNull()
        night.genre shouldBe "experimental, techno"
        night.subtitle.shouldBeNull()
        night.priceNote shouldBe "Donation at the door"
        night.ticketUrl shouldBe "https://ra.co/events/2555788"
        night.imageUrl shouldBe "https://giri.berlin/wp-content/uploads/2026/10/synestransia-freedom-of-5-Miguel-Wilson-960x1200.jpg"
        night.eventType shouldBe "PARTY"
        night.artists shouldContainExactly listOf("ANNARA", "DJ Trade Trainer", "MADAKYA", "TA 7O").map { ScrapedArtist(it, ArtistRole.DJ) }
    }

    @Test
    fun `each night of the month carries its own facts and four acts`() {
        with(event("GIRI x Precision.wav")) {
            eventDate shouldBe LocalDate.of(2026, 11, 25)
            doorsTime shouldBe LocalTime.of(18, 0)
            genre shouldBe "House & Electro minimal"
            ticketUrl shouldBe "https://ra.co/events/2567991"
            sourceUrl shouldBe "https://giri.berlin/programme/2026-11-25-giri-x-precision-wav--9a06b65f62f6483500e50466/"
            artists.map { it.name } shouldContainExactly listOf("Gian", "Caldo", "Paganipagani", "Djinni")
        }
    }

    @Test
    fun `the description keeps the paragraphs before the lineup and drops the link lines`() {
        val description = event("GIRI x Precision.wav").description!!

        description shouldStartWith "Giri x Precision.wav"
        description shouldContain "\n\nSome friendships begin on a dancefloor"
        description shouldNotContain "Lineup"
        description shouldNotContain "Instagram"
    }

    @Test
    fun `an empty month yields no events`() {
        scrape("giri-month-2026-12.html", "https://giri.berlin/programme/?month=2026-12").shouldBeEmpty()
    }

    @Test
    fun `a calendar link without a template is skipped`() {
        scrapeHtml(
            """<a class="calendar-event" data-event="form-x" data-event-date="2026-11-18" href="https://giri.berlin/programme/x/"></a>"""
        ).shouldBeEmpty()
    }

    @Test
    fun `a calendar link listed twice yields one event`() {
        val html = night("") + """<a class="calendar-event" data-event="form-x" data-event-date="2026-11-18" href="#"></a>"""

        scrapeHtml(html).map { it.sourceId } shouldContainExactly listOf("giri:form-x")
    }

    @Test
    fun `a Time fact is the start, and its end rolls past midnight`() {
        val html = night("<div><dt>Doors</dt><dd>18:30</dd></div><div><dt>Time</dt><dd>19:00–1:00</dd></div>", category = "food")

        with(scrapeHtml(html).single()) {
            doorsTime shouldBe LocalTime.of(18, 30)
            startTime shouldBe LocalTime.of(19, 0)
            endTime shouldBe LocalTime.of(1, 0)
            endDate shouldBe LocalDate.of(2026, 11, 19)
            eventType shouldBe "OTHER"
            artists.shouldBeEmpty()
        }
    }

    @Test
    fun `an open-ended doors value stores the doors time and no end`() {
        with(scrapeHtml(night("<div><dt>Doors</dt><dd>From 18:00-end</dd></div>")).single()) {
            doorsTime shouldBe LocalTime.of(18, 0)
            startTime.shouldBeNull()
            endTime.shouldBeNull()
        }
    }

    @Test
    fun `a night whose every act plays live is a concert of headliners`() {
        val lineup = "<p>Leng Seong (live)</p><p>Memeshift (Live Performance)</p><p><a href=\"#\">Instagram · @act</a></p>"

        with(scrapeHtml(night("", lineup)).single()) {
            eventType shouldBe "CONCERT"
            artists shouldContainExactly listOf("Leng Seong", "Memeshift").map { ScrapedArtist(it, ArtistRole.HEADLINER) }
        }
    }

    @Test
    fun `a live act on a DJ night is billed live and a guest placeholder is dropped`() {
        val lineup = "<ul><li>James Demon — <a href=\"#\">RA</a></li><li>Nicky Miller (live)</li><li>Special Guest</li></ul>"

        scrapeHtml(night("", lineup)).single().artists shouldContainExactly
            listOf(ScrapedArtist("James Demon", ArtistRole.DJ), ScrapedArtist("Nicky Miller", ArtistRole.LIVE))
    }

    @Test
    fun `a long title is kept whole`() {
        val title = "GIRI x " + "Very Long Collaboration Name ".repeat(8).trim()

        scrapeHtml(night("", title = title)).single().title shouldBe title
    }
}
