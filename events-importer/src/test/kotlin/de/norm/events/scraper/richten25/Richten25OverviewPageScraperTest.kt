package de.norm.events.scraper.richten25

import de.norm.events.event.EventType
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class Richten25OverviewPageScraperTest {
    private val baseUrl = "https://richten25.de/events"
    private val events = Richten25OverviewPageScraper().scrape(fixtureDocument("richten25/richten25-events.html", baseUrl), baseUrl)

    @Test
    fun `reads the upcoming nights from the embed in the island props and skips the past ones`() {
        events.size shouldBe 8
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 15)
        events.last().eventDate shouldBe LocalDate.of(2026, 10, 31)
        events.first().sourceId shouldBe "richten25:2026-10-15"
        events.forEach { it.eventType shouldBe EventType.CONCERT.name }
        events.first().title shouldBe "EKO: Daniel Erdmann, Kalle Kalima & Mia Ohlmeier"
    }

    @Test
    fun `bills each musician of a set, and a band for a prefixed set`() {
        events.first { it.eventDate == LocalDate.of(2026, 10, 20) }.artists.map { it.name } shouldBe
            listOf("Chino Shuichi", "Asger Thomsen", "Devin Gray", "Mia Dyberg", "Rieko Okuda")
        events.first().artists.map { it.name } shouldBe listOf("EKO")
        events.first { it.eventDate == LocalDate.of(2026, 10, 23) }.artists.map { it.name } shouldBe listOf("Jon Elbaz Sextet")
    }

    @Test
    fun `bills a series' members rather than its name`() {
        val html =
            """<astro-island props="{&quot;x&quot;:[0,&quot;&lt;section class=\&quot;event-list\&quot;&gt;&lt;h2&gt;Upcoming Events&lt;/h2&gt;""" +
                """&lt;ul&gt;&lt;li&gt;&lt;strong&gt;October 4, 2026: &lt;/strong&gt; Knistern Series: Otis Mensah &amp; Nour Sokhon // Jumoke Adeyanju""" +
                """&lt;/li&gt;&lt;/ul&gt;&lt;/section&gt;&quot;]}"></astro-island>"""

        val night = Richten25OverviewPageScraper().scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

        night.artists.map { it.name } shouldBe listOf("Otis Mensah", "Nour Sokhon", "Jumoke Adeyanju")
    }

    @Test
    fun `bills each act of a set joined by a plus`() {
        val html =
            """<astro-island props="{&quot;x&quot;:[0,&quot;&lt;section class=\&quot;event-list\&quot;&gt;&lt;h2&gt;Upcoming Events&lt;/h2&gt;""" +
                """&lt;ul&gt;&lt;li&gt;&lt;strong&gt;November 5, 2026: &lt;/strong&gt; """ +
                """Christoph Gallio, Dominic Lash &amp; Mark Sanders // Ackern 76 + Chris Pitsiokos""" +
                """&lt;/li&gt;&lt;/ul&gt;&lt;/section&gt;&quot;]}"></astro-island>"""

        val night = Richten25OverviewPageScraper().scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

        night.artists.map { it.name } shouldBe listOf("Christoph Gallio", "Dominic Lash", "Mark Sanders", "Ackern 76", "Chris Pitsiokos")
    }

    @Test
    fun `bills the members of a night named before its colon, without its more-to-come tail`() {
        val night = events.first { it.eventDate == LocalDate.of(2026, 10, 21) }

        night.artists.map { it.name } shouldBe
            listOf(
                "Laura.aLL",
                "Carla Boregas",
                "Giuliana Corsi",
                "Marina Cyrino",
                "Stephanie Fernandes",
                "Matthias Koole",
                "Manuel Pessôa de Lima",
                "Kaloan Meenochite",
                "Sofia Salvo",
                "M.Takara"
            )
    }

    @Test
    fun `bills the performers after a count of sets, and the band before it`() {
        val night = nightOf("TV-Play: Axel Dörner &amp; Sayori Izawa // 3 SOLOS: Siri Salminen / Jean-Luc Guionnet / Lotus Eddé Khouri")

        night.artists.map { it.name } shouldBe listOf("TV-Play", "Siri Salminen", "Jean-Luc Guionnet", "Lotus Eddé Khouri")
    }

    @Test
    fun `reads any count of solos, duos, trios or sets as a format label`() {
        nightOf("2 Duos: Anna Webber &amp; Ben Kono").artists.map { it.name } shouldBe listOf("Anna Webber", "Ben Kono")
        nightOf("1 Set: Ava Mendoza").artists.map { it.name } shouldBe listOf("Ava Mendoza")
        nightOf("4 trios: Ada Ryu, Ben Ott").artists.map { it.name } shouldBe listOf("Ada Ryu", "Ben Ott")
    }

    @Test
    fun `bills a band whose name only starts with a number`() {
        nightOf("3 Doors Down: Brad Arnold &amp; Chris Henderson").artists.map { it.name } shouldBe listOf("3 Doors Down")
        nightOf("3 Solos Ensemble: Ada Ryu &amp; Ben Ott").artists.map { it.name } shouldBe listOf("3 Solos Ensemble")
        nightOf("Sumpf Trio: Tkacz, Daszkiewicz &amp; Doskocz").artists.map { it.name } shouldBe listOf("Sumpf Trio")
    }

    @Test
    fun `bills nobody for a night whose text after its colon is a timetable, and starts it at the concert`() {
        val night = nightOf("Taiwanese Music in Live Exchange: Workshops: 14:30–17:30 Concert: 19:30–21:00")

        night.title shouldBe "Taiwanese Music in Live Exchange"
        night.startTime shouldBe LocalTime.of(19, 30)
        night.artists.shouldBeEmpty()
    }

    @Test
    fun `leaves the start open for a timetable with no concert time`() {
        val night = nightOf("Listening Session: Doors 18:00, Talk 19:00")

        night.title shouldBe "Listening Session"
        night.startTime shouldBe null
        night.artists.shouldBeEmpty()
    }

    @Test
    fun `reads a night with no clock time as a lineup with no start`() {
        val night = nightOf("Die Enttäuschung: Mahall, Dörner, Roder &amp; Tom")

        night.startTime shouldBe null
        night.artists.map { it.name } shouldBe listOf("Die Enttäuschung")
        events.forEach { it.startTime shouldBe null }
    }

    @Test
    fun `reads a date line with no comma and a start before its colon, and bills the acts a presenter names`() {
        val night =
            nightOf(
                "Est.Discipline presents \\&quot;𝐵𝑜𝑔𝑒𝓃\\&quot;: Yves B Golden &amp;#x26; Cr00k6",
                dateLine = "November 19 2026 (starts at 19:30)"
            )

        night.eventDate shouldBe LocalDate.of(2026, 11, 19)
        night.sourceId shouldBe "richten25:2026-11-19"
        night.startTime shouldBe LocalTime.of(19, 30)
        night.title shouldBe "Est.Discipline presents \"𝐵𝑜𝑔𝑒𝓃\": Yves B Golden & Cr00k6"
        night.artists.map { it.name } shouldBe listOf("Yves B Golden", "Cr00k6")
    }

    @Test
    fun `reads a start before the colon after a date with its comma, and a date with neither`() {
        nightOf("Ada Ryu", dateLine = "November 2, 2026 (starts at 20:00)").startTime shouldBe LocalTime.of(20, 0)
        nightOf("Ada Ryu", dateLine = "November 2, 2026 (Starts at 9:00)").startTime shouldBe LocalTime.of(9, 0)
        with(nightOf("Ada Ryu", dateLine = "November 2 2026")) {
            eventDate shouldBe LocalDate.of(2026, 11, 2)
            startTime shouldBe null
        }
    }

    @Test
    fun `starts a timetable at its concert time over the date line's, and at the date line's when it names no concert`() {
        val timetable = "Listening Session: Doors 18:00, Talk 19:00"

        nightOf("$timetable Concert: 20:30", dateLine = "November 2 2026 (starts at 18:00)").startTime shouldBe LocalTime.of(20, 30)
        nightOf(timetable, dateLine = "November 2 2026 (starts at 18:00)").startTime shouldBe LocalTime.of(18, 0)
    }

    @Test
    fun `skips a night whose date line has no year`() {
        nightsOf("Ada Ryu", dateLine = "November 19 (starts at 19:30)").shouldBeEmpty()
    }

    @Test
    fun `yields nothing for a page without the embed`() {
        Richten25OverviewPageScraper().scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    /** One night in the embed's shape; the lineup is escaped once, as the island's attribute carries it. */
    private fun nightOf(
        lineup: String,
        dateLine: String = "November 2, 2026"
    ) = nightsOf(lineup, dateLine).single()

    private fun nightsOf(
        lineup: String,
        dateLine: String
    ) = Richten25OverviewPageScraper()
        .scrape(
            Jsoup.parse(
                """<astro-island props="{&quot;x&quot;:[0,&quot;&lt;section class=\&quot;event-list\&quot;&gt;&lt;h2&gt;Upcoming Events&lt;/h2&gt;""" +
                    """&lt;ul&gt;&lt;li&gt;&lt;strong&gt;$dateLine: &lt;/strong&gt; $lineup""" +
                    """&lt;/li&gt;&lt;/ul&gt;&lt;/section&gt;&quot;]}"></astro-island>""",
                baseUrl
            ),
            baseUrl
        )
}
