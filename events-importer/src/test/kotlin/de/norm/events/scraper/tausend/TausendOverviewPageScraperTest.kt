package de.norm.events.scraper.tausend

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class TausendOverviewPageScraperTest {
    private val scraper = TausendOverviewPageScraper()
    private val baseUrl = "https://tausendberlin.com/lineup/"
    private val document = fixtureDocument("tausend/tausend-overview.html", baseUrl)
    private val events = scraper.scrape(document, baseUrl)

    @Test
    fun `reads every night in the lineup graph`() {
        events shouldHaveSize 18
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 8)
        events.last().eventDate shouldBe LocalDate.of(2026, 12, 5)
        events.map { it.sourceId }.toSet() shouldHaveSize 18
    }

    @Test
    fun `parses a DJ night with its series, full text, image and Resident Advisor link`() {
        val night = events.first { it.title == "Carla Valenti" }

        night.subtitle shouldBe "Tausend House"
        night.eventType shouldBe EventType.PARTY.name
        night.eventDate shouldBe LocalDate.of(2026, 10, 9)
        night.startTime shouldBe LocalTime.of(21, 0)
        night.endTime.shouldBeNull()
        night.sourceId shouldBe "tausend:2026-10-09-carla-valenti"
        night.sourceUrl shouldBe "https://tausendberlin.com/lineup/#act-carla-valenti-20261009"
        night.ticketUrl shouldBe "https://ra.co/events/2552196"
        night.imageUrl shouldBe "https://tausendberlin.com/wp-content/uploads/2026/10/BarTausend-Dj-CarlaValentini-sw-02.webp"
        night.description!! shouldStartWith "Die chilenische DJ und Produzentin"
        night.description shouldContain "\nValenti lebt heute in Barcelona"
        night.artists shouldContainExactly listOf(ScrapedArtist(name = "Carla Valenti", role = ArtistRole.DJ))
        night.status shouldBe "SCHEDULED"
    }

    @Test
    fun `keeps the text the graph's description cuts short`() {
        val night = events.first { it.title == "House Queens" }

        night.description!! shouldContain "Ab 22 Uhr eröffnet A.N.A.I.K.A. die musikalische Nacht"
    }

    @Test
    fun `types a Tausend Live night as a concert with the band as one headliner`() {
        val night = events.first { it.title == "Olla & The Groove Markers" }

        night.eventType shouldBe EventType.CONCERT.name
        night.artists shouldContainExactly listOf(ScrapedArtist(name = "Olla & The Groove Markers", role = ArtistRole.HEADLINER))
    }

    @Test
    fun `splits a Tausend Live act that adds a DJ into the singer and the DJ`() {
        val night =
            scraper
                .scrape(Jsoup.parse(liveNight("Desney Bailey & DJ Jauche"), baseUrl), baseUrl)
                .single()

        night.title shouldBe "Desney Bailey & DJ Jauche"
        night.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "Desney Bailey", role = ArtistRole.HEADLINER),
                ScrapedArtist(name = "DJ Jauche", role = ArtistRole.DJ)
            )
    }

    @Test
    fun `splits a Tausend Live act whose credit line names a live act and a DJ set`() {
        // Event 13490.
        val night =
            scraper
                .scrape(Jsoup.parse(liveNight("Flashbaxx & Daniel Paul", FLASHBAXX_TEXT), baseUrl), baseUrl)
                .single()

        night.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "Flashbaxx", role = ArtistRole.HEADLINER),
                ScrapedArtist(name = "Daniel Paul", role = ArtistRole.DJ)
            )
    }

    @Test
    fun `keeps a Tausend Live band whole when the credit line names someone else`() {
        val night =
            scraper
                .scrape(Jsoup.parse(liveNight("Olla & The Groove Markers", FLASHBAXX_TEXT), baseUrl), baseUrl)
                .single()

        night.artists shouldContainExactly listOf(ScrapedArtist(name = "Olla & The Groove Markers", role = ArtistRole.HEADLINER))
    }

    @Test
    fun `splits a shared DJ bill`() {
        val night = events.first { it.subtitle == "Yacht Week Party" }

        night.artists.map { it.name } shouldContainExactly listOf("Bombata", "Florian Kepler", "Mambi Dexter")
    }

    @Test
    fun `puts a 24 H start on the printed night and names nobody for a series without a DJ`() {
        val night = events.first { it.title == "Le Salon Privé" }

        night.eventDate shouldBe LocalDate.of(2026, 10, 16)
        night.startTime shouldBe LocalTime.of(23, 59)
        night.subtitle.shouldBeNull()
        night.artists.shouldBeEmpty()
        night.ticketUrl.shouldBeNull()
    }

    @Test
    fun `keys the English page's text on the same source ids`() {
        val englishUrl = "https://tausendberlin.com/en/lineup/"
        val english = scraper.descriptions(fixtureDocument("tausend/tausend-overview-en.html", englishUrl))

        english.keys shouldBe events.map { it.sourceId }.toSet()
        english.getValue("tausend:2026-10-09-carla-valenti")!! shouldStartWith "The Chilean DJ and producer"
    }

    @Test
    fun `drops a closed private night and keeps the public night beside it`() {
        val nights =
            scraper.scrape(
                Jsoup.parse(
                    night("Closed Event · Bar Tausend", "2026-11-13", CLOSED_TEXT) +
                        night("Carla Valenti · Tausend House", "2026-11-14", "Ab 21 Uhr legt sie auf."),
                    baseUrl
                ),
                baseUrl
            )

        nights.map { it.title } shouldContainExactly listOf("Carla Valenti")
    }

    @Test
    fun `keeps a public night whose text names the closed booking before it`() {
        val night =
            scraper
                .scrape(
                    Jsoup.parse(
                        night("Le Salon Privé", "2026-11-20", "Zunächst findet eine geschlossene Veranstaltung statt. Ab 24 Uhr öffnet die Bar."),
                        baseUrl
                    ),
                    baseUrl
                ).single()

        night.title shouldBe "Le Salon Privé"
    }

    @Test
    fun `drops a night named Closed Event even without its text`() {
        scraper
            .scrape(Jsoup.parse(night("Closed Event · Bar Tausend", "2026-11-13", null), baseUrl), baseUrl)
            .shouldBeEmpty()
    }

    @Test
    fun `returns nothing for a page without a lineup`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    /** Event 13348's shape: a graph entry and the `.bt-lu-body` block that carries the full text. */
    private fun night(
        name: String,
        date: String,
        text: String?
    ): String {
        val act = name.substringBefore(" · ")
        val body =
            text
                ?.let {
                    "<div class=\"bt-lu-body\"><div class=\"bt-lu-date\"><time datetime=\"${date}T21:00:00+01:00\"></time></div>" +
                        "<h3 class=\"bt-lu-name\">$act</h3><div class=\"bt-lu-text\"><p>$it</p></div></div>"
                }.orEmpty()
        return """
            <script type="application/ld+json">
            {"@context":"https://schema.org","@type":"MusicEvent","name":"$name",
             "startDate":"${date}T21:00:00+01:00","performer":{"@type":"PerformingGroup","name":"$act"}}
            </script>
            $body
            """.trimIndent()
    }

    /** Event 11778's graph entry, cut to the fields the parser reads. */
    private fun liveNight(
        act: String,
        description: String = ""
    ): String =
        """
        <script type="application/ld+json">
        {"@context":"https://schema.org","@type":"MusicEvent","name":"$act · Tausend Live","description":"$description",
         "startDate":"2026-12-09T21:00:00+01:00","url":"https://tausendberlin.com/lineup/#act-desney-bailey-dj-jauche-20261209"}
        </script>
        """.trimIndent()

    private companion object {
        const val FLASHBAXX_TEXT =
            "Für das DJ-Set kommt Daniel Paul dazu. Mittwoch, 2. Dezember 2026 · Flashbaxx live & Daniel Paul DJ-Set · 21 H"
        const val CLOSED_TEXT =
            "Ab 21 Uhr findet in der Bar Tausend eine geschlossene Veranstaltung statt. Ab 00:00 Uhr öffnet die Bar für alle."
    }
}
