package de.norm.events.scraper.eightmm

import de.norm.events.artist.canonicalArtistName
import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class EightMmEventPageScraperTest {
    private val scraper = EightMmEventPageScraper()

    private fun listed(title: String) =
        ScrapedEvent(
            title = title,
            eventDate = LocalDate.of(2026, 10, 17),
            startTime = LocalTime.of(19, 0),
            sourceUrl = "https://www.8mmbar.de/program/2026/10/17/x",
            sourceId = "eight_mm:x"
        )

    @Test
    fun `reads the blurb and the doors and show times`() {
        val event = scraper.enrich(listed("RMFC LIVE in 8MM"), fixtureDocument("eightmm/eightmm-event-rmfc.html", "https://www.8mmbar.de/program"))

        event.description!! shouldStartWith "ROUGH’n’ROLL Concerts presents:\n\nR.M.F.C. (Sydney, Australia)"
        event.description shouldContain "Aftershow DJ set Liam Carrol"
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.startTime shouldBe LocalTime.of(20, 30)
    }

    @Test
    fun `bills a festival day from the blurb's line-up list`() {
        // Event 12287: the title names the day, and the acts are only in the blurb.
        val html =
            """
            <article class="eventitem"><div class="eventitem-column-content"><div class="sqs-html-content">
            <p>8MM Fest returns for two nights.</p><p>Line up -</p>
            <p>ABIGAIL TOLL / ACTIVEMIRROR / BLACK BORDELLO /CALVIN LOVE / I WANT POETRY / YARD + MORE</p>
            </div></div></article>
            """.trimIndent()

        val event = scraper.enrich(listed("8MM FEST DAY 1"), Jsoup.parse(html))

        event.artists.map { it.name } shouldBe
            listOf("Abigail Toll", "Activemirror", "Black Bordello", "Calvin Love", "I Want Poetry", "Yard")
    }

    @Test
    fun `drops the bracket after an act in the line-up list`() {
        // The 8MM Fest warm-up show on 2026-11-12, whose blurb bills the Copenhagen act as `WEDDING (CPN)`.
        val html =
            """
            <article class="eventitem"><div class="eventitem-column-content"><div class="sqs-html-content">
            <p>Line up - WEDDING (CPN) / LYZZA / MAF</p>
            </div></div></article>
            """.trimIndent()

        val event = scraper.enrich(listed("8MM Fest WARM UP SHOW"), Jsoup.parse(html))

        event.artists.map { it.name } shouldBe listOf("Wedding", "Lyzza", "Maf")
    }

    @Test
    fun `keeps the acts a house-frame title bills over a line-up list`() {
        val html =
            """<article class="eventitem"><div class="eventitem-column-content"><div class="sqs-html-content"><p>Line up: SOMEONE ELSE</p></div></div></article>"""
        val listed = listed("8MM Live: SOFT PALMS").copy(artists = listOf(ScrapedArtist(name = "SOFT PALMS", role = ArtistRole.HEADLINER)))

        scraper.enrich(listed, Jsoup.parse(html)).artists.map { it.name } shouldBe listOf("SOFT PALMS")
    }

    private fun billed(
        title: String,
        blurb: String = "<p>A blurb.</p>",
        eventType: EventType = EventType.CONCERT
    ) = scraper
        .enrich(
            listed(title).copy(eventType = eventType.name),
            Jsoup.parse("""<article class="eventitem"><div class="eventitem-column-content"><div class="sqs-html-content">$blurb</div></div></article>""")
        ).artists

    // Event 15198.
    @Test
    fun `bills the act in front of Live plus`() {
        val act = billed("Martin Bisi Band Live plus the screening of short film BC45").single()

        act.name shouldBe "Martin Bisi Band"
        act.role shouldBe ArtistRole.HEADLINER
        act.titleDerived shouldBe true
        // A night's frame in front of the act is no part of its name.
        billed("Playroom: BLANCO TETA Live plus friends").shouldBeEmpty()
        billed("A Night with BLANCO TETA Live plus friends").shouldBeEmpty()
    }

    // Event 15197.
    @Test
    fun `bills the act after LIVE and a dash`() {
        val act = billed("PLAYROOM LIVE - BLANCO TETA").single()

        canonicalArtistName(act.name) shouldBe "Blanco Teta"
        act.role shouldBe ArtistRole.HEADLINER
        act.titleDerived shouldBe true
        billed("PLAYROOM LIVE - BLANCO TETA (CANCELLED)").map { it.name } shouldBe listOf("BLANCO TETA")
        billed("PLAYROOM LIVE - TBA").shouldBeEmpty()
    }

    // Event 15196.
    @Test
    fun `bills the act between with and LIVE, and not the house's DJs`() {
        val act = billed("24 Years 8MM with LES BIG BYRD LIVE + 8MM All star Djs").single()

        canonicalArtistName(act.name) shouldBe "Les Big Byrd"
        act.role shouldBe ArtistRole.HEADLINER
        act.titleDerived shouldBe true
        billed("PLAYROOM LIVE - BLANCO TETA + 8MM All star Djs").map { it.name } shouldBe listOf("BLANCO TETA")
    }

    @Test
    fun `a title in no LIVE shape bills nobody`() {
        billed("KOROWA — A Night of Live and Dark Sounds").shouldBeEmpty()
        billed("Romantic Fiction and Reformation Club present: Equinoxious live at 8MM.").shouldBeEmpty()
        billed("8MM X UNKNOWN CALLERS HALLOWEEN").shouldBeEmpty()
        billed("A Very Long Night of ${"Endless ".repeat(40)}Feedback LIVE").shouldBeEmpty()
    }

    @Test
    fun `reads a LIVE title only on a concert, and after the line-up list`() {
        billed("PLAYROOM LIVE - BLANCO TETA", eventType = EventType.PARTY).shouldBeEmpty()
        billed("PLAYROOM LIVE - BLANCO TETA", blurb = "<p>Line up - YARD / LYZZA</p>").map { it.name } shouldBe listOf("Yard", "Lyzza")
        billed("PLAYROOM LIVE - BLANCO TETA", blurb = "").map { it.name } shouldBe listOf("BLANCO TETA")
    }

    @Test
    fun `a page without a blurb leaves the listed event`() {
        val listed = listed("JEFF CLARK (BLACK LIPS) SUNDAY'S IN OCTOBER DJ RESIDENCY")

        val event = scraper.enrich(listed, fixtureDocument("eightmm/eightmm-event-residency.html", "https://www.8mmbar.de/program"))

        event shouldBe listed
        event.description.shouldBeNull()
    }
}
