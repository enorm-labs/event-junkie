package de.norm.events.scraper.festsaal

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.norm.events.scraper.LogFields
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [FestsaalApiScraper].
 *
 * Parses a saved snapshot of Festsaal Kreuzberg's Wagtail CMS API response
 * (`/api/v2/pages/?type=home.EventPage`) for deterministic, offline-safe testing
 * without HTTP fetching.
 */
class FestsaalApiScraperTest {
    private val scraper = FestsaalApiScraper()

    private val events: List<ScrapedEvent> by lazy {
        val json =
            javaClass.classLoader
                .getResourceAsStream("scraper/festsaal/festsaal-api.json")!!
                .bufferedReader()
                .readText()
        scraper.scrape(json)
    }

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    @Test
    fun `parses every event in the API response`() {
        events shouldHaveSize 78
    }

    @Test
    fun `maps all fields of a representative concert`() {
        val elenaRose = event("festsaal:elena-rose-2026")
        elenaRose.title shouldBe "ELENA ROSE"
        elenaRose.subtitle shouldBe "EUROPE TOUR 2026"
        elenaRose.eventType shouldBe "CONCERT"
        elenaRose.eventDate shouldBe LocalDate.of(2026, 7, 10)
        elenaRose.doorsTime shouldBe LocalTime.of(19, 30)
        elenaRose.startTime shouldBe LocalTime.of(20, 30)
        elenaRose.genre shouldBe "Pop"
        elenaRose.imageUrl shouldBe
            "https://v2.files.funkhaus.io/fsk-1170-media/original_images/ELENA-ROSE-EUROPA-TOUR_1920x1005_event_fb.jpg"
        elenaRose.ticketUrl shouldBe "https://ticket.deputamadreclub.eu/elena26bln/"
        elenaRose.status shouldBe "SCHEDULED"
        elenaRose.soldOut shouldBe false
        elenaRose.artists shouldContainExactly listOf(ScrapedArtist("ELENA ROSE", "HEADLINER", titleDerived = true))
    }

    @Test
    fun `mints no artist for a league billed under its own name`() {
        val dltlly = event("festsaal:dltlly-september-2026")
        dltlly.title shouldBe "DLTLLY"
        dltlly.subtitle shouldBe "13th Birthday"
        dltlly.eventType shouldBe "CONCERT"
        dltlly.genre shouldBe "Hip-Hop"
        dltlly.artists.shouldBeEmpty()
    }

    @Test
    fun `builds the public source URL and stable sourceId from the slug, not the admin host`() {
        val elenaRose = event("festsaal:elena-rose-2026")
        elenaRose.sourceUrl shouldBe "https://festsaal-kreuzberg.de/de/programm/elena-rose-2026/"
        elenaRose.sourceId shouldBe "festsaal:elena-rose-2026"
    }

    @Test
    fun `appends the structured support act after the title headliner`() {
        val internationalMusic = event("festsaal:INTERNATIONAL-MUSIC")
        internationalMusic.genre shouldBe "Krautrock"
        internationalMusic.artists shouldContainExactly
            listOf(
                ScrapedArtist("INTERNATIONAL MUSIC", "HEADLINER", titleDerived = true),
                ScrapedArtist("Gregor", "SUPPORT")
            )
    }

    @Test
    fun `captures the sold-out flag without changing the status`() {
        val bosse = event("festsaal:bosse-2026")
        bosse.soldOut shouldBe true
        bosse.status shouldBe "SCHEDULED"
    }

    @Test
    fun `maps a moved-date event to POSTPONED on its new date`() {
        val festaJunina = event("festsaal:festa-junina-26")
        festaJunina.status shouldBe "POSTPONED"
        // The event now happens on changed_date (2026-08-01), not the original date (2026-06-27).
        festaJunina.eventDate shouldBe LocalDate.of(2026, 8, 1)
    }

    // Holly Humberstone's show carried this on 2026-09-27 and read as on (#1816).
    @Test
    fun `maps a moved-unknown event to POSTPONED on its original date`() {
        val postponed = scraper.scrape(apiWith(status = "moved_unknown")).single()

        postponed.status shouldBe "POSTPONED"
        postponed.eventDate shouldBe LocalDate.of(2026, 9, 27)
    }

    @Test
    fun `defaults an unknown status to SCHEDULED and names the event in the warning`() {
        val (events, warnings) = withWarnings { scraper.scrape(apiWith(status = "moved_somewhere")) }

        events.single().status shouldBe "SCHEDULED"
        warnings
            .single()
            .keyValuePairs
            .single { it.key == LogFields.EVENT_SOURCE_ID }
            .value shouldBe "festsaal:holly-humberstone-2026"
    }

    @Test
    fun `maps a transferred event to RELOCATED`() {
        event("festsaal:live-wrestling-07-2026").status shouldBe "RELOCATED"
    }

    @Test
    fun `treats a custom status as scheduled and parses the plain decimal price`() {
        val mokaEfti = event("festsaal:moka-efti")
        mokaEfti.status shouldBe "SCHEDULED"
        mokaEfti.pricePresale shouldBe BigDecimal("51.80")
    }

    @Test
    fun `tolerates a missing genre`() {
        event("festsaal:bluthund-festsaal").genre.shouldBeNull()
    }

    @Test
    fun `types a wrestling show as SHOW and mints no artist from its title`() {
        val wrestling = event("festsaal:live-wrestling-07-2026")
        wrestling.eventType shouldBe "SHOW"
        wrestling.artists.shouldBeEmpty()
    }

    @Test
    fun `types a festa as PARTY and mints no artist from its title`() {
        val festaJunina = event("festsaal:festa-junina-26")
        festaJunina.eventType shouldBe "PARTY"
        festaJunina.artists.shouldBeEmpty()
    }

    @Test
    fun `types markets and open-air event series as OTHER with no artists`() {
        val market = event("festsaal:Japanmarkt-Dezember-26")
        market.eventType shouldBe "OTHER"
        market.artists.shouldBeEmpty()

        val openAir = event("festsaal:Just-Taylor-Open-Air")
        openAir.eventType shouldBe "OTHER"
        openAir.artists.shouldBeEmpty()
    }

    @Test
    fun `keeps a concert whose subtitle - not title - is an open-air format note`() {
        // "¡Wepa! Bunny" (subtitle "Open Air"): the open-air marker is only an event-name
        // signal in the title, so this stays a concert with its headliner intact.
        val wepaBunny = event("festsaal:wepa-bunny")
        wepaBunny.eventType shouldBe "CONCERT"
        wepaBunny.artists shouldContainExactly listOf(ScrapedArtist("¡Wepa! Bunny", "HEADLINER", titleDerived = true))
    }

    @Test
    fun `mints no artist for a denylisted party series despite accents and a Berlin suffix`() {
        // "Bohème Sauvage Berlin" stays CONCERT (no keyword), but the shared denylist —
        // now accent- and city-normalized — filters the title so no headliner is created.
        event("festsaal:Bohème-Sauvage-Berlin").artists.shouldBeEmpty()
    }

    @Test
    fun `returns an empty list when the payload has no items`() {
        scraper.scrape("""{"meta":{"total_count":0},"items":[]}""").shouldBeEmpty()
    }

    @Test
    fun `returns an empty list for a payload without an items array`() {
        scraper.scrape("{}").shouldBeEmpty()
    }

    @Test
    fun `returns an empty list for an unparseable body`() {
        scraper.scrape("not json at all").shouldBeEmpty()
    }

    /** One event with [status], shaped like the live API row; `changed_date` is null, as it was. */
    private fun apiWith(status: String): String =
        """
        {"items": [{"id": 1, "meta": {"slug": "holly-humberstone-2026"}, "title": "Holly Humberstone",
          "date": "2026-09-27", "status": "$status", "changed_date": null, "changed_text": ""}]}
        """.trimIndent()

    private fun <T> withWarnings(block: () -> T): Pair<T, List<ILoggingEvent>> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        return try {
            block() to appender.list.filter { it.level == ch.qos.logback.classic.Level.WARN }
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
    }
}
