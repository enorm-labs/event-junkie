package de.norm.events.scraper.humboldtforum

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.fixtureText
import de.norm.events.scraper.isForChildren
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/** 40 items of the `/wp-json/humboldt/v1/event/items?lang=de` response saved on 2026-11-15, a few of each kind. */
class HumboldtForumApiScraperTest {
    private val events = HumboldtForumApiScraper().scrape(fixtureText("humboldtforum/humboldtforum-items.json"), SOURCE_URL)

    private fun rows(postId: Long) = events.filter { it.sourceId == "humboldt_forum:$postId" || it.sourceId.startsWith("humboldt_forum:$postId-") }

    private fun row(id: String) = events.single { it.sourceId == "humboldt_forum:$id" }

    @Test
    fun `keeps one row per date of the stage, talk and screening programme and one per exhibition run`() {
        events shouldHaveSize 70
        events.map { it.sourceId }.toSet().size shouldBe events.size
    }

    @Test
    fun `reads a concert date fully`() {
        val concert = row("170175-202611131900")

        concert.title shouldBe "Musical Belongings VIII: Dschingis Lab"
        concert.eventType shouldBe EventType.CONCERT.name
        concert.eventDate shouldBe LocalDate.of(2026, 11, 13)
        concert.startTime shouldBe LocalTime.of(19, 0)
        concert.endDate shouldBe LocalDate.of(2026, 11, 13)
        concert.endTime shouldBe LocalTime.of(21, 0)
        concert.pricePresale shouldBe BigDecimal("20")
        concert.priceNote.shouldBeNull()
        concert.free shouldBe false
        concert.ticketUrl shouldBe "https://tickets.humboldtforum.org/#/product/event/363?date=2026-11-13&date_id=30397&lang=de"
        concert.sourceUrl shouldBe "https://www.humboldtforum.org/de/programm/termin/konzert/musical-belongings-viii-dschingis-lab-170175/"
        concert.imageUrl!!.startsWith("https://www.humboldtforum.org/wp-content/uploads/2026/08/") shouldBe true
        concert.status shouldBe EventStatus.SCHEDULED.name
        rows(170175) shouldHaveSize 4
    }

    @Test
    fun `ends a DJ set and an opening at midnight on the next day`() {
        val dj = row("174491-202611272100")
        dj.eventType shouldBe EventType.PARTY.name
        dj.endDate shouldBe LocalDate.of(2026, 11, 28)
        dj.endTime shouldBe LocalTime.MIDNIGHT
        dj.room shouldBe "Foyer"

        row("171634-202611271700").endDate shouldBe LocalDate.of(2026, 11, 28)
    }

    @Test
    fun `drops an end before the start that is no night`() {
        val slip = row("172835-202611291530")

        slip.startTime shouldBe LocalTime.of(15, 30)
        slip.endTime.shouldBeNull()
        slip.endDate.shouldBeNull()
    }

    @Test
    fun `stores an exhibition as one run from its opening to its closing day`() {
        val run = row("165896")

        run.title shouldBe "Mirae kh Rhee Weiterreichen"
        run.eventType shouldBe EventType.EXHIBITION.name
        run.eventDate shouldBe LocalDate.of(2026, 6, 18)
        run.endDate shouldBe LocalDate.of(2027, 2, 15)
        run.startTime.shouldBeNull()
        run.room shouldBe "3. OG, Raum 318, Raum 319"
        run.pricePresale shouldBe BigDecimal("9")
        rows(145814).single().endDate shouldBe LocalDate.of(2026, 11, 30)
    }

    @Test
    fun `drops an exhibition run with no end, which is a permanent display`() {
        rows(170998).shouldBeEmpty()
    }

    @Test
    fun `marks a cancelled date and keeps the others`() {
        val teaWay = rows(33291)

        teaWay shouldHaveSize 16
        teaWay.filter { it.status == EventStatus.CANCELLED.name }.map { it.sourceId } shouldContainExactlyInAnyOrder
            listOf("humboldt_forum:33291-202611221300", "humboldt_forum:33291-202611221500")
        teaWay.first().eventType shouldBe EventType.SHOW.name
    }

    @Test
    fun `reads a booked, a joined, a registered and an unstated status as scheduled`() {
        row("170175-202611131900").status shouldBe EventStatus.SCHEDULED.name
        row("172891-202611272000").status shouldBe EventStatus.SCHEDULED.name
        row("172395-202611251700").status shouldBe EventStatus.SCHEDULED.name
        row("171634-202611281100").status shouldBe EventStatus.SCHEDULED.name
    }

    @Test
    fun `sets free for Kostenfrei and a free-entry line, but not for free entry with a museum ticket`() {
        row("172891-202611272000").free shouldBe true
        row("172891-202611272000").priceNote.shouldBeNull()
        row("172395-202611251700").free shouldBe true
        row("172656-202611291600").free shouldBe true

        val teaWay = row("33291-202610251300")
        teaWay.free shouldBe false
        teaWay.priceNote shouldBe "kostenfrei zzgl. Ausstellungsticket"
    }

    @Test
    fun `reads free entry from the ticket text when the planned price is blank`() {
        val logoskop = row("173565-202701271800")
        logoskop.free shouldBe true
        logoskop.priceNote.shouldBeNull()
    }

    @Test
    fun `keeps free entry beside a museum ticket as the note, from the ticket text and from a date's info`() {
        val dragStoryHour = row("173508-202611221300")
        dragStoryHour.free shouldBe false
        dragStoryHour.priceNote shouldBe "kostenfrei zzgl. Ausstellungseintritt"

        val mysticDimensions = row("72781-202601031700")
        mysticDimensions.free shouldBe false
        mysticDimensions.priceNote shouldBe "Teilnahme kostenfrei – Ausstellungsticket notwendig"
    }

    @Test
    fun `prices each date from its own info`() {
        val priced = row("72781-202612051700")
        priced.pricePresale shouldBe BigDecimal("9")
        priced.priceNote.shouldBeNull()
    }

    @ParameterizedTest(name = "price \"{0}\", info \"{1}\", ticket text \"{2}\"")
    @CsvSource(
        delimiter = '|',
        nullValues = ["-"],
        value = [
            "12 EUR     | kostenfrei                                       | kostenfrei                              | false | 12 | -",
            "''         | Führung in deutscher Sprache - 9 EUR             | kostenfrei                              | false | 9  | -",
            "''         | ''                                               | kostenfrei                              | true  | -  | -",
            "''         | kostenfrei - Anmeldung unter w-masked@example.de | -                                       | true  | -  | -",
            "''         | Bitte T-Shirt mitbringen                         | Ein Abend kostenfrei, bitte buchen Sie  | false | -  | -",
            "''         | -                                                | Bitte buchen Sie telefonisch: 030 12345678 | false | -  | -",
            "''         | Teilnahme kostenfrei, Anmeldung unter w-masked@example.de | -                              | false | -  | -",
            "-          | -                                                | Teilnahme auf Einladung                 | false | -  | -"
        ]
    )
    fun `prices a date from the planned price, then its info, then the ticket text, and never from booking prose`(
        price: String?,
        info: String?,
        ticketText: String?,
        free: Boolean,
        presale: BigDecimal?,
        note: String?
    ) {
        val event = HumboldtForumApiScraper().scrape(priceItem(price, info, ticketText), SOURCE_URL).single()

        event.free shouldBe free
        event.pricePresale shouldBe presale
        event.priceNote shouldBe note
    }

    private fun priceItem(
        price: String?,
        info: String?,
        ticketText: String?
    ): String {
        fun field(
            name: String,
            value: String?
        ) = value?.let { """"$name":"$it",""" }.orEmpty()
        return """
            {"events":[{"postId":1,"title":"Ein Abend","type":"event","permalink":"https://www.humboldtforum.org/de/programm/termin/1/",
              "genre_details":[{"text":"Konzert"}],
              "acf":{${field("tickettext", ticketText)}"planning":{${field("price", price)}"admission":""},
                "times":[{${field("info", info)}"start":"20261201 19:00","status":"BUY"}]}}]}
            """.trimIndent()
    }

    @Test
    fun `keeps a long title whole, with its no-break space collapsed`() {
        row("172656-202611291600").title shouldBe "Wer hat, der hat? - Über Erbe, Chancen und Verantwortung"
    }

    @Test
    fun `marks the picture-book and story-theatre series for children, and the talks not`() {
        rows(40501).map { it.isForChildren }.toSet() shouldBe setOf(true)
        rows(35163).map { it.isForChildren }.toSet() shouldBe setOf(true)
        rows(173508).map { it.isForChildren }.toSet() shouldBe setOf(true)
        rows(35163).first().eventType shouldBe EventType.SHOW.name
        row("174072-202611271900").isForChildren shouldBe false
    }

    @Test
    fun `reads a series that has no dates of its own as no event`() {
        rows(31647).shouldBeEmpty()
        rows(33827).shouldBeEmpty()
        rows(172399).shouldBeEmpty()
    }

    @Test
    fun `keeps one row for a date the API lists twice`() {
        rows(172853) shouldHaveSize 1
    }

    @Test
    fun `drops tours, workshops and every item filed under one beside a kept category`() {
        listOf(29663L, 171171L, 107832L, 173510L, 30247L).flatMap(::rows).shouldBeEmpty()
    }

    @Test
    fun `drops the market, an installation, a symposium, a programme page and the digital, publication and permanent items`() {
        listOf(105384L, 171095L, 173967L, 24718L, 128041L, 170268L, 46021L, 14132L, 20343L).flatMap(::rows).shouldBeEmpty()
    }

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource(
        "Konzert, CONCERT",
        "DJ Set, PARTY",
        "Lesung, READING",
        "Literatur, READING",
        "Poetry, READING",
        "Gespräch, READING",
        "Diskurs, READING",
        "Wissenschaft, READING",
        "Tanz, SHOW",
        "Schauspiel, SHOW",
        "Vorführung, SHOW",
        "Performance, SHOW",
        "Film/Video, SCREENING",
        "Ausstellung, EXHIBITION"
    )
    fun `types each kept category`(
        category: String,
        type: EventType
    ) {
        typeOf(listOf(category)) shouldBe type
    }

    @ParameterizedTest(name = "{0} types nothing")
    @CsvSource(
        "Publikation",
        "Digital",
        "Kunst am Bau",
        "Essen und Trinken",
        "Einkaufen",
        "Must see",
        "Thema",
        "Installation",
        "Markt",
        "Sonstige",
        "Konferenz/Symposium",
        "Programm"
    )
    fun `types no other category`(category: String) {
        typeOf(listOf(category)).shouldBeNull()
    }

    @Test
    fun `types a reading on stage as a show`() {
        typeOf(listOf("Lesung", "Schauspiel")) shouldBe EventType.SHOW
        typeOf(listOf("Ausstellung", "Must see")) shouldBe EventType.EXHIBITION
    }

    @ParameterizedTest(name = "{0} drops the item")
    @CsvSource("Führung", "Workshop", "Drop-In", "Fortbildung", "Meetup")
    fun `drops a participation format beside a kept category`(category: String) {
        val json =
            """
            {"events":[{"postId":1,"title":"Ein Abend","type":"event","permalink":"https://www.humboldtforum.org/de/programm/termin/1/",
              "genre_details":[{"text":"$category"},{"text":"Konzert"}],"acf":{"times":[{"start":"20261201 19:00","end":"20:00","status":"BUY"}]}}]}
            """.trimIndent()

        HumboldtForumApiScraper().scrape(json, SOURCE_URL).shouldBeEmpty()
    }

    @ParameterizedTest(name = "{0} bills {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "170175-202611131900 | Dschingis Lab",
            "172913-202611271900 | Canto Diáspora",
            "172891-202611272000 | Nana Benz du Togo",
            "172926-202611281630 | Leonel Vásquez",
            "173684-202612111930 | FAYIM, OMAR KTD, SXCL"
        ]
    )
    fun `bills a concert's acts from its subline or its title`(
        id: String,
        acts: String
    ) {
        val artists = row(id).artists

        artists.map { it.name } shouldBe acts.split(", ")
        artists.map { it.role }.toSet() shouldBe setOf(ArtistRole.HEADLINER)
    }

    @Test
    fun `bills a DJ night's DJ from its title`() {
        val artists = row("174491-202611272100").artists

        artists.map { it.name to it.role } shouldBe listOf("Maurício Fleury" to ArtistRole.DJ)
    }

    @Test
    fun `bills nobody at a reading, a talk or a screening`() {
        events.filter { it.eventType !in setOf(EventType.CONCERT.name, EventType.PARTY.name) }.flatMap { it.artists }.shouldBeEmpty()
        actsOf(EventType.READING, "Ein Abend", "Mit Anna Muster und Bert Beispiel").shouldBeEmpty()
    }

    @Test
    fun `bills no act from a title that names the house`() {
        actsOf(EventType.CONCERT, "Queer Pop bei BERLIN GLOBAL", null).shouldBeEmpty()
    }

    @Test
    fun `bills no composer from a subline crediting the music`() {
        actsOf(EventType.CONCERT, "Nana Benz du Togo", "Mit Musik von Johann Sebastian Bach").map { it.name } shouldBe
            listOf("Nana Benz du Togo")
        actsOf(EventType.CONCERT, "Nana Benz du Togo", "Ein Abend mit Werken von Clara Schumann").map { it.name } shouldBe
            listOf("Nana Benz du Togo")
    }

    @Test
    fun `splits a comma list without a final conjunction into its names`() {
        actsOf(EventType.CONCERT, "Abend", "Mit FAYIM, OMAR KTD").map { it.name } shouldBe listOf("FAYIM", "OMAR KTD")
    }

    @Test
    fun `reads an empty list as no events`() {
        HumboldtForumApiScraper().scrape("""{"events":[]}""", SOURCE_URL).shouldBeEmpty()
    }

    private companion object {
        const val SOURCE_URL = "https://www.humboldtforum.org/wp-json/humboldt/v1/event/items?lang=de"
    }
}
