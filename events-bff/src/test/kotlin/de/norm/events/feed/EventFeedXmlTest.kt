package de.norm.events.feed

import de.norm.events.event.EventFilter
import de.norm.events.event.EventFilterParams
import de.norm.events.event.EventType
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import javax.xml.parsers.DocumentBuilderFactory

class EventFeedXmlTest {
    private fun feed(
        locale: String,
        filter: EventFilter,
        venueName: String? = null
    ): Document =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(EventFeedXml.render(locale, "https://event-junkie.de/feed.xml", emptyList(), filter, venueName).toByteArray()))

    private fun Document.channelText(name: String): String =
        getElementsByTagName("channel")
            .item(0)
            .childNodes
            .let { nodes -> (0 until nodes.length).map { nodes.item(it) }.single { it.nodeName == name }.textContent }

    private fun title(
        locale: String,
        filter: EventFilter,
        venueName: String? = null
    ) = feed(locale, filter, venueName).channelText("title")

    private fun description(
        locale: String,
        filter: EventFilter,
        venueName: String? = null
    ) = feed(locale, filter, venueName).channelText("description")

    @ParameterizedTest(name = "{0} in {1}")
    @MethodSource("titleRows")
    fun `names at most a type and a venue in the title, as the issue's table says`(
        @Suppress("UNUSED_PARAMETER") row: String,
        locale: String,
        params: EventFilterParams,
        expected: String
    ) {
        title(locale, params.toFilter(), venueName = params.venue?.let { "Lido" }) shouldBe expected
    }

    @Test
    fun `falls back to the filtered title for a type or a venue that does not exist`() {
        title("en", EventFilterParams(eventType = listOf("opera")).toFilter()) shouldBe "Event Junkie — new events (filtered)"
        title("de", EventFilterParams(venue = "gone").toFilter(), venueName = null) shouldBe "Event Junkie — neue Veranstaltungen (gefiltert)"
    }

    @Test
    fun `keeps a long venue name whole, escaped, in title and description`() {
        val name = "Kulturbrauerei — Maschinenhaus & Kesselhaus <Open-Air> im Hof der alten Schultheiss-Brauerei"
        val filter = EventFilterParams(eventType = listOf("concert"), venue = "kulturbrauerei").toFilter()

        title("en", filter, name) shouldBe "Event Junkie — new concerts at $name"
        title("de", filter, name) shouldBe "Event Junkie — neue Konzerte: $name"
        description("en", filter, name) shouldBe
            "The events in Berlin that Event Junkie found most recently. Filters: event type: concerts · venue: $name."
    }

    @Test
    fun `leaves an unfiltered feed's description unchanged`() {
        description("en", EventFilter()) shouldBe "The events in Berlin that Event Junkie found most recently."
        description("de", EventFilter()) shouldBe "Die Veranstaltungen in Berlin, die Event Junkie zuletzt gefunden hat."
    }

    @Test
    fun `lists every active filter in the description, in both locales`() {
        val filter =
            EventFilterParams(
                eventType = listOf("party", "concert", "opera"),
                venue = "lido",
                district = listOf("kreuzberg", "mitte"),
                venueType = listOf("club"),
                artist = "sam-prekop",
                promoter = "36-concerts",
                genre = "techno",
                family = listOf("electronic"),
                minPrice = BigDecimal("10.50"),
                maxPrice = BigDecimal("20.00"),
                q = "  late   night ",
                excludeSoldOut = true,
                free = true,
                timeOfDay = listOf("late", "evening", "dawn")
            ).toFilter()

        description("en", filter, "Lido") shouldBe
            "The events in Berlin that Event Junkie found most recently. Filters: " +
            "event type: concerts, OPERA, parties · venue: Lido · district: kreuzberg, mitte · venue type: club · artist: sam-prekop · " +
            "promoter: 36-concerts · genre: techno · genre family: electronic · presale price: 10.5–20 € · search: “late night” · " +
            "time of night: dawn, evening, late · no sold-out events · free only."
        description("de", filter, "Lido") shouldBe
            "Die Veranstaltungen in Berlin, die Event Junkie zuletzt gefunden hat. Filter: " +
            "Veranstaltungsart: Konzerte, OPERA, Partys · Location: Lido · Bezirk: kreuzberg, mitte · Location-Art: club · " +
            "Künstler: sam-prekop · Veranstalter: 36-concerts · Genre: techno · Genrefamilie: electronic · Vorverkaufspreis: 10,5–20 € · " +
            "Suche: „late night“ · Tageszeit: dawn, abends, nachts · ohne ausverkaufte · nur kostenlose."
    }

    @Test
    fun `names a one-sided price range and an unknown venue by its slug`() {
        description("en", EventFilterParams(minPrice = BigDecimal("15")).toFilter()) shouldBe
            "The events in Berlin that Event Junkie found most recently. Filters: presale price: from 15 €."
        description("de", EventFilterParams(maxPrice = BigDecimal("7.5"), venue = "gone").toFilter()) shouldBe
            "Die Veranstaltungen in Berlin, die Event Junkie zuletzt gefunden hat. Filter: Location: gone · Vorverkaufspreis: bis 7,5 €."
    }

    @Test
    fun `has a plural for every event type in both locales`() {
        listOf("en", "de").forEach { locale ->
            EventType.entries.forEach { FeedFilterText.eventTypePlural(locale, it.name).shouldNotBeNull() }
        }
    }

    companion object {
        private val CONCERT = listOf("concert")
        private const val LIDO = "lido"

        @JvmStatic
        fun titleRows(): List<Arguments> =
            listOf(
                row("none", "en", EventFilterParams(), "Event Junkie — new events"),
                row("none", "de", EventFilterParams(), "Event Junkie — neue Veranstaltungen"),
                row("one event type", "en", EventFilterParams(eventType = CONCERT), "Event Junkie — new concerts"),
                row("one event type", "de", EventFilterParams(eventType = CONCERT), "Event Junkie — neue Konzerte"),
                row("one venue", "en", EventFilterParams(venue = LIDO), "Event Junkie — new at Lido"),
                row("one venue", "de", EventFilterParams(venue = LIDO), "Event Junkie — neu: Lido"),
                row("type + venue", "en", EventFilterParams(eventType = CONCERT, venue = LIDO), "Event Junkie — new concerts at Lido"),
                row("type + venue", "de", EventFilterParams(eventType = CONCERT, venue = LIDO), "Event Junkie — neue Konzerte: Lido"),
                row(
                    "three facets",
                    "en",
                    EventFilterParams(eventType = CONCERT, venue = LIDO, genre = "techno"),
                    "Event Junkie — new events (filtered)"
                ),
                row(
                    "three facets",
                    "de",
                    EventFilterParams(eventType = CONCERT, venue = LIDO, genre = "techno"),
                    "Event Junkie — neue Veranstaltungen (gefiltert)"
                ),
                row("several types", "en", EventFilterParams(eventType = listOf("concert", "party")), "Event Junkie — new events (filtered)"),
                row("several types", "de", EventFilterParams(eventType = listOf("concert", "party")), "Event Junkie — neue Veranstaltungen (gefiltert)"),
                row("one other facet", "en", EventFilterParams(free = true), "Event Junkie — new events (filtered)"),
                row("one other facet", "de", EventFilterParams(district = listOf("mitte")), "Event Junkie — neue Veranstaltungen (gefiltert)"),
                row("one type twice", "en", EventFilterParams(eventType = listOf("concert", "CONCERT")), "Event Junkie — new concerts")
            )

        private fun row(
            name: String,
            locale: String,
            params: EventFilterParams,
            expected: String
        ) = Arguments.of(name, locale, params, expected)
    }
}
