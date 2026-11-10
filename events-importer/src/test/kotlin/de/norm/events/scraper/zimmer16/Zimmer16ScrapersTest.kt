package de.norm.events.scraper.zimmer16

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.fixtureDocument
import de.norm.events.scraper.forChildrenPhrase
import de.norm.events.scraper.isForChildren
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class Zimmer16ScrapersTest {
    private val baseUrl = "https://zimmer16.com/"
    private val events = Zimmer16OverviewPageScraper().scrape(fixtureDocument("zimmer16/zimmer16-home.html", baseUrl), baseUrl)
    private val eventPage = Zimmer16EventPageScraper()
    private val childrensUrl = "https://zimmer16.com/programm-fuer-kinder-mikado-e-v/"
    private val childrens = Zimmer16OverviewPageScraper().scrape(fixtureDocument("zimmer16/zimmer16-kinder.html", childrensUrl), childrensUrl)

    @Test
    fun `reads the homepage's 20 cards with their year and drops the audience from the title`() {
        events shouldHaveSize 20
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 8)
        events.last().eventDate shouldBe LocalDate.of(2026, 10, 31)
        events.forEach { it.title shouldNotContain "(Erwachsene)" }
        val bourbon = events.first { it.title == "Claude Bourbon" }
        bourbon.sourceId shouldBe "zimmer_16:claude-bourbon-erwachsene-10-10-26"
        bourbon.sourceUrl shouldBe "https://www.yesticket.org/event/de/claude-bourbon-erwachsene-10-10-26"
        bourbon.eventType shouldBe EventType.CONCERT.name
        bourbon.artists.shouldBeEmpty()
        bourbon.imageUrl shouldBe "https://zimmer16.com/wp-json/yesticket/v1/picture/28683"
    }

    @Test
    fun `moves a support act out of the title and types the house's other formats`() {
        val rotlicht = events.first { it.title == "Rotlicht" }
        rotlicht.subtitle shouldBe "Support: Isolisation"
        rotlicht.artists.map { it.name to it.role } shouldBe listOf("Isolisation" to ArtistRole.SUPPORT)
        eventPage.enrich(rotlicht, Jsoup.parse(yesticketPage("<p>Rotlicht spielen Postpunk.</p>"), baseUrl)).shouldNotBeNull().artists.map {
            it.name to it.role
        } shouldBe listOf("Rotlicht" to ArtistRole.HEADLINER, "Isolisation" to ArtistRole.SUPPORT)
        events.first { it.title.startsWith("So noch nie") }.eventType shouldBe EventType.READING.name
        events.first { it.title.contains("Kreisler Revue") }.eventType shouldBe EventType.SHOW.name
    }

    @Test
    fun `bills no headliner on a concert card and nobody on another format's tagline`() {
        events
            .filter { it.eventType == EventType.CONCERT.name }
            .flatMap { it.artists }
            .map { it.role }
            .distinct() shouldBe listOf(ArtistRole.SUPPORT)
        events.first { it.title.startsWith("Kaléko") }.artists.shouldBeEmpty()
    }

    @Test
    fun `types improv as comedy and a night that calls itself a show as a show`() {
        val types = scrapeCards(TYPED_TITLES).associate { it.title to it.eventType }

        // Events 13388 and 13404.
        types["Improtheater – Im Freien Fall"] shouldBe EventType.COMEDY.name
        types["A CHRISTMAS CAROL – eine literarisch-musikalische Spook Show, Maria Jamborsky"] shouldBe EventType.SHOW.name
        types["Showcase Trio Berlin"] shouldBe EventType.CONCERT.name
        types["Le Tomboy Release Tour, The Murk Prince"] shouldBe EventType.CONCERT.name
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("reportedConcerts", "earlierConcerts")
    fun `bills only the act the event page's text repeats`(
        event: String,
        title: String,
        text: String,
        expected: List<String>
    ) {
        val card = scrapeCards(listOf(title)).single()

        withClue("event $event") {
            eventPage
                .enrich(card, Jsoup.parse(yesticketPage("<p>$text</p>"), baseUrl))
                .shouldNotBeNull()
                .artists
                .map { it.name } shouldBe expected
        }
    }

    @Test
    fun `adds the time, the end, the price and the full text from the event page`() {
        val bourbon = events.first { it.title == "Claude Bourbon" }

        val enriched = eventPage.enrich(bourbon, fixtureDocument("zimmer16/yesticket-claude-bourbon.html", bourbon.sourceUrl)).shouldNotBeNull()

        enriched.startTime shouldBe LocalTime.of(20, 0)
        enriched.endDate shouldBe LocalDate.of(2026, 10, 10)
        enriched.endTime shouldBe LocalTime.of(22, 0)
        enriched.priceBoxOffice shouldBe BigDecimal("14.00")
        enriched.soldOut shouldBe false
        enriched.imageUrl shouldBe bourbon.imageUrl
        enriched.artists.map { it.name } shouldBe listOf("Claude Bourbon")
        val text = enriched.description.shouldNotBeNull()
        text shouldStartWith "Es ist nicht einfach"
        text shouldContain "spanischer Gitarre."
        text shouldNotContain "Hinweis"
    }

    @Test
    fun `marks a sold-out night`() {
        val zillmer = events.first { it.title == "Arno Zillmer" }

        eventPage.enrich(zillmer, fixtureDocument("zimmer16/yesticket-arno-zillmer.html", zillmer.sourceUrl)).shouldNotBeNull().soldOut shouldBe true
    }

    @Test
    fun `stores a free night at 0 euros but not a pay-what-you-want one`() {
        val lesebuehne = events.first { it.title.startsWith("So noch nie") }

        val free = eventPage.enrich(lesebuehne, Jsoup.parse(yesticketPage("<p>Eintritt frei</p>"), baseUrl)).shouldNotBeNull()
        free.free shouldBe true
        free.priceNote.shouldBeNull()
        val payWhatYouWant = eventPage.enrich(lesebuehne, Jsoup.parse(yesticketPage("<p>Eintritt frei, but pay what you want</p>"), baseUrl)).shouldNotBeNull()
        payWhatYouWant.free shouldBe false
        payWhatYouWant.priceBoxOffice shouldBe lesebuehne.priceBoxOffice
        payWhatYouWant.priceNote shouldBe "Eintritt frei, but pay what you want"
        payWhatYouWant.description shouldBe "Eintritt frei, but pay what you want"
    }

    @Test
    fun `notes only the pay-what-you-want sentence, and only on a 0 euro night`() {
        val lesebuehne = events.first { it.title.startsWith("So noch nie") }
        val text = "<p>Für Künstler ab 19:00 Uhr geöffnet. Eintritt frei, pay what you want. Bringt Texte mit!</p><p>Moderation: Lothar</p>"

        eventPage.enrich(lesebuehne, Jsoup.parse(yesticketPage(text), baseUrl)).shouldNotBeNull().priceNote shouldBe "Eintritt frei, pay what you want."
        val priced = eventPage.enrich(lesebuehne, Jsoup.parse(yesticketPage(text, price = "8.00"), baseUrl)).shouldNotBeNull()
        priced.priceBoxOffice shouldBe BigDecimal("8.00")
        priced.priceNote.shouldBeNull()
    }

    @Test
    fun `yields nothing for an event page without its JSON-LD`() {
        eventPage.enrich(events.first(), Jsoup.parse("<html><body></body></html>", baseUrl)).shouldBeNull()
        Zimmer16OverviewPageScraper().scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    private fun yesticketPage(
        paragraphs: String,
        price: String = "0.00"
    ): String =
        """
        <script type="application/ld+json">
        {"@context": "https://schema.org", "@type": "Event", "startDate": "2026-10-26T19:30:00",
         "offers": {"@type": "Offer", "price": "$price", "priceCurrency": "EUR"}}
        </script>
        <div class="spacer--C">$paragraphs</div>
        """.trimIndent()

    private fun scrapeCards(titles: List<String>) = Zimmer16OverviewPageScraper().scrape(Jsoup.parse(cards(titles), baseUrl), baseUrl)

    private fun cards(titles: List<String>): String =
        titles.withIndex().joinToString("") { (day, title) ->
            """
            <a href="https://www.yesticket.org/event/de/card-${day + 1}-erwachsene/">
              <div class="ytp-event-card">
                <span class="ytp-event-card-month">Nov.</span><span class="ytp-event-card-day">${day + 1}</span>
                <span class="ytp-event-card-year">2026</span>
                <strong class="ytp-event-card-title">$title (Erwachsene)</strong>
              </div>
            </a>
            """.trimIndent()
        }

    @Test
    fun `marks the children's page cards for children, as shows, and leaves the homepage unmarked`() {
        childrens shouldHaveSize 24
        childrens.all { it.isForChildren && it.forChildrenPhrase == "(Kinder)" } shouldBe true
        childrens.none { it.title.endsWith("(Kinder)") } shouldBe true
        childrens.map { it.eventType }.distinct() shouldBe listOf(EventType.SHOW.name)
        childrens.first().title shouldBe "Wusel und der goldene Ring – Theater Katinchen"
        events.none { it.isForChildren } shouldBe true
    }

    private companion object {
        val TYPED_TITLES =
            listOf(
                "Improtheater – Im Freien Fall",
                "A CHRISTMAS CAROL – eine literarisch-musikalische Spook Show, Maria Jamborsky",
                "Showcase Trio Berlin",
                "Le Tomboy Release Tour, The Murk Prince"
            )

        /** The reported titles of #4829 and a sentence of their event page's text. */
        @JvmStatic
        fun reportedConcerts(): List<Arguments> =
            listOf(
                Arguments.of("13387", "Weihnachtsgeflüster & Saitenklang, Tom Kahler", "Tom Kahler, gelernter Schauspieler, lädt ein.", listOf("Tom Kahler")),
                Arguments.of(
                    "13389",
                    "Für immer Weihnachten? Manfred Maurenbrecher Solo",
                    "Manfred Maurenbrecher fährt an diesem Abend auf, was es zu entdecken gibt.",
                    listOf("Manfred Maurenbrecher")
                ),
                Arguments.of(
                    "13390",
                    "Lenya träumt vom Broadway, Eine Hommage an Lotte Lenya",
                    "Der musikalische Vortragsabend erzählt das Leben von Lotte Lenya.",
                    emptyList<String>()
                ),
                Arguments.of(
                    "13393",
                    "Ja, Schatz. … und andere Kompromisse Julia Alsheimer",
                    "Mit urkomischen Geschichten gibt Julia Alsheimer Antworten auf alle Beziehungsfragen.",
                    listOf("Julia Alsheimer")
                ),
                Arguments.of("13394", "Stormy Saturdays – Bock auf Rock?", "Stormy Saturdays spielen bekannte Hits.", listOf("Stormy Saturdays")),
                Arguments.of(
                    "13395",
                    "Friederike Ziegler, Zieh Dich aus Petronella",
                    "… ist nicht nur ein Song von Tucholsky, interpretiert von Friederike Ziegler (Schauspielerin).",
                    listOf("Friederike Ziegler")
                ),
                Arguments.of("13397", "saxyarpa – klingt himmlisch und groovt wie Hölle", "Gleich, ob saxyarpa eigene Songs spielen.", listOf("saxyarpa")),
                Arguments.of(
                    "13398",
                    "Tobias Panwitz Folksong-Reisen",
                    "Seit Jahren gehört Tobias Panwitz zum Kern der Folk-Szene.",
                    listOf("Tobias Panwitz")
                ),
                Arguments.of(
                    "13400",
                    "between the trees and me",
                    "Das Berliner Duo „between the trees and me“ webt mit zwei Stimmen.",
                    listOf("between the trees and me")
                ),
                Arguments.of("13401", "Stolzmann & friends", "Stolzmann & friends ist mehr als nur eine Band.", listOf("Stolzmann & friends")),
                Arguments.of(
                    "13404",
                    "A CHRISTMAS CAROL – eine literarisch-musikalische Spook Show, Maria Jamborsky",
                    "Der Abend zitiert das historische Genre der \"Spook Show\".",
                    emptyList<String>()
                )
            )

        /** Earlier production titles (#3164) and the guards beside them. */
        @JvmStatic
        fun earlierConcerts(): List<Arguments> =
            listOf(
                Arguments.of("7194", "Soundtrack to Sunset, Landmusik", "Denn „Landmusik“ meint auch das ländliche Leben.", emptyList<String>()),
                Arguments.of("7197", "Le Tomboy Release Tour, The Murk Prince", "Le Tomboy ist das Album von The Murk Prince.", listOf("The Murk Prince")),
                Arguments.of("7206", "Lea Willms and MUD/O", "Lea Willms singt. MUD/O ist eine Band aus Warschau.", listOf("Lea Willms", "MUD/O")),
                Arguments.of(
                    "9094",
                    "Grand Cinema Jens Saleh und Oliver Bostroem",
                    "Jens Saleh spielt Gitarre, Oliver Bostroem Saxophon.",
                    emptyList<String>()
                ),
                Arguments.of("7182", "Jörg Bobrik: Folk – Blues – Americana", "Jörg Bobrik spielt Folk und Blues.", listOf("Jörg Bobrik")),
                Arguments.of("the open stage", "Offene Bühne", "Die Offene Bühne ist das Beste am Montag.", emptyList<String>()),
                Arguments.of(
                    "an act the text does not repeat",
                    "Calum Baird – Scottish Singer-Songwriter Live",
                    "Ein Abend aus Schottland.",
                    emptyList<String>()
                )
            )
    }
}
