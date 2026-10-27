package de.norm.events.scraper.giri

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
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
    private val octoberUrl = "https://giri.berlin/programme/"
    private val novemberUrl = "https://giri.berlin/programme/?month=2026-11"

    private fun scrape(
        fixture: String,
        url: String
    ): List<ScrapedEvent> {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/giri/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, url), url)
    }

    private val october: List<ScrapedEvent> by lazy { scrape("giri-overview.html", octoberUrl) }

    private fun event(titlePrefix: String) = october.single { it.title.startsWith(titlePrefix) }

    @Test
    fun `scrape reads every listed night with its date from the calendar`() {
        october shouldHaveSize 5
        october.map { it.eventDate } shouldContainExactly listOf(21, 22, 23, 24, 28).map { LocalDate.of(2026, 10, it) }
        october.map { it.sourceId }.distinct() shouldHaveSize 5
    }

    @Test
    fun `scrape reads the event key from the programme_event link the site uses since 2026-10`() {
        val live = scrape("giri-overview-2026-10-17.html", octoberUrl)
        live shouldHaveSize 5
        live.map { it.eventDate } shouldContainExactly listOf(21, 22, 23, 24, 28).map { LocalDate.of(2026, 10, it) }
        live.first().sourceId shouldBe "${EventSource.GIRI.sourceIdPrefix}f513b2e799a62e7115a31481"
    }

    @Test
    fun `scrape reads a fully populated night`() {
        val night = event("GIRI x CURRENTS")

        night.sourceId shouldBe "giri:f513b2e799a62e7115a31481"
        night.sourceUrl shouldBe "https://giri.berlin/programme/?month=2026-10&view=list#event-form-c4364ff1b452ed792b3855ba322c5fca"
        night.startTime shouldBe LocalTime.of(18, 0)
        night.endTime.shouldBeNull()
        night.endDate.shouldBeNull()
        night.genre shouldBe "experimental, IDM, noise, video"
        night.subtitle.shouldBeNull()
        night.priceNote shouldBe "Donation-based"
        night.ticketUrl shouldBe "https://ra.co/events/2545224"
        night.imageUrl shouldBe "https://giri.berlin/wp-content/themes/giri-2026/assets/images/events/a5706e7a915488c6-960.webp"
        night.eventType shouldBe "CONCERT"
        night.artists shouldContainExactly listOf("BAERK.", "Leng Seong", "Memeshift").map { ScrapedArtist(it, ArtistRole.HEADLINER) }
    }

    @Test
    fun `the description keeps the paragraphs before the lineup and drops the sections after it`() {
        val description = event("Kontralamakina").description!!

        description shouldStartWith "Kontralamakina presents KNTRLMKN09"
        description shouldContain "\n\nTen raw techno tracks"
        description shouldNotContain "Lineup"
        description shouldNotContain "Mastering"
    }

    @Test
    fun `a night without live markers is a party of DJs`() {
        val night = event("Kontralamakina")

        night.eventType shouldBe "PARTY"
        night.typeIsFallback shouldBe true
        night.artists shouldContainExactly listOf("Nases Morur", "Thravsma", "Fuerza Kontraria").map { ScrapedArtist(it, ArtistRole.DJ) }
    }

    @Test
    fun `a lineup with one list per act skips the link items`() {
        event("Giri x Rawa Club").artists.map { it.name } shouldContainExactly
            listOf("hotel99*", "Merma Suelo", "Parallel Nico", "Dj Hidrataccioni")
    }

    @Test
    fun `a lineup written as paragraphs skips the link paragraphs`() {
        event("Satelliet Studio").artists.map { it.name } shouldContainExactly listOf("Aton", "Daraio", "Linda Shiro", "Otis")
    }

    @Test
    fun `a live act on a DJ night is billed live and a guest placeholder is dropped`() {
        event("GIRI X OCCULTISTS").artists shouldContainExactly
            listOf(ScrapedArtist("James Demon", ArtistRole.DJ), ScrapedArtist("Nicky Miller", ArtistRole.LIVE))
    }

    @Test
    fun `a later month page reads its nights, a hybrid live and DJ set counting as live`() {
        val november = scrape("giri-month-2026-11.html", novemberUrl)

        november shouldHaveSize 1
        with(november.single()) {
            eventDate shouldBe LocalDate.of(2026, 11, 5)
            sourceUrl shouldStartWith "https://giri.berlin/programme/?month=2026-11&view=list#event-form-"
            eventType shouldBe "CONCERT"
            artists.map { it.name } shouldContainExactly listOf("Andrew K. Morrison", "Andreagene", "Marie Dior", "High Fall")
        }
    }

    @Test
    fun `an empty month yields no events`() {
        scrape("giri-month-2026-12.html", "https://giri.berlin/programme/?month=2026-12").shouldBeEmpty()
    }

    @Test
    fun `an entry the calendar does not date is skipped`() {
        val html =
            """
            <article class="programme-detail" id="event-form-x"><h2><a href="https://giri.berlin/programme/?giri_event=abc">Night</a></h2></article>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html, octoberUrl), octoberUrl).shouldBeEmpty()
    }

    @Test
    fun `a printed end time is read with its date`() {
        val html =
            """
            <div class="giri-calendar-day" data-calendar-date="2026-10-21"><a class="giri-calendar-event" data-event-id="form-x" href="#"></a></div>
            <article class="programme-detail" id="event-form-x" data-event-category="food">
              <h2><a href="https://giri.berlin/programme/?giri_event=abc">Jazz Dinner</a></h2>
              <dl class="event-facts"><div><dt>Time</dt><dd>19:00–1:00</dd></div></dl>
            </article>
            """.trimIndent()

        with(scraper.scrape(Jsoup.parse(html, octoberUrl), octoberUrl).single()) {
            startTime shouldBe LocalTime.of(19, 0)
            endTime shouldBe LocalTime.of(1, 0)
            endDate shouldBe LocalDate.of(2026, 10, 22)
            artists.shouldBeEmpty()
        }
    }
}
