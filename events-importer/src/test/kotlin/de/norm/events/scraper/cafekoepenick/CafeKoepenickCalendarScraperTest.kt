package de.norm.events.scraper.cafekoepenick

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Tests for [CafeKoepenickCalendarScraper], on the public sub-calendar's feed saved on 2026-10-17. */
class CafeKoepenickCalendarScraperTest {
    private val feedUrl =
        "https://export.kalender.digital/ics/2710942/25872d7326c4cadce48e/ffentlicheveranstaltungenangebote.ics?past_months=0&future_months=12"

    private val events: List<ScrapedEvent> by lazy {
        val calendar =
            javaClass.classLoader
                .getResourceAsStream("scraper/cafekoepenick/cafe-koepenick-calendar.ics")!!
                .bufferedReader()
                .readText()
        CafeKoepenickCalendarScraper().scrape(calendar, feedUrl)
    }

    private fun first(title: String): ScrapedEvent = events.first { it.title == title }

    @Test
    fun `keeps the band nights, parties, stage nights and the open jam, and drops the club's other diary entries`() {
        // 258 entries; the Küfa, counselling, meetings, games nights, outings and the chimney sweep are dropped.
        events shouldHaveSize 79
        events.count { it.title == "Open Jam/ Open Mic" } shouldBe 52
        events.count { it.title == "Queerer Tresen" } shouldBe 14
        val dropped = listOf("Küfa", "Manege Beratung", "Breaking News", "Spieleabend", "Cafe goes", "Schornsteinfeger", "Gedenken", "Weihnachtsfeier")
        events.none { event -> dropped.any { it in event.title } } shouldBe true
    }

    @Test
    fun `types each entry by the format its title names`() {
        val types = events.associate { it.title to it.eventType }

        types["Freshly married + Kalte Knie"] shouldBe "CONCERT"
        types["Open Jam/ Open Mic"] shouldBe "CONCERT"
        types["Weihnachten vergessen"] shouldBe "CONCERT"
        types["FLINTA & Krawall"] shouldBe "CONCERT"
        types["Halloween Party"] shouldBe "PARTY"
        types["Queerer Tresen"] shouldBe "PARTY"
        types["Theaterstück \"Germaniab*tch\""] shouldBe "SHOW"
        types["Lesung \"Die Stille Gewalt. Wie der Staat Frauen allein lässt.\""] shouldBe "READING"
        types["HipHop Festival"] shouldBe "FESTIVAL"
    }

    @Test
    fun `maps a timed entry from UTC to Berlin time and links the calendar page`() {
        val night = first("Freshly married + Kalte Knie")

        night.eventDate shouldBe LocalDate.of(2026, 10, 17)
        night.startTime shouldBe LocalTime.of(19, 0)
        night.endDate shouldBe LocalDate.of(2026, 10, 17)
        night.endTime shouldBe LocalTime.of(20, 0)
        night.sourceUrl shouldBe "https://kalender.digital/25872d7326c4cadce48e"
        night.sourceId shouldBe "cafe_koepenick:KDIG3234134963"
        night.description.shouldBeNull()
        night.artists shouldContainExactly listOf(ScrapedArtist("Freshly married"), ScrapedArtist("Kalte Knie"))
        first("Theaterstück \"Germaniab*tch\"").startTime shouldBe LocalTime.of(20, 0)
    }

    @Test
    fun `reads an all-day entry as a date with no time, and a two-day one up to its last day`() {
        val halloween = first("Halloween Party")
        halloween.eventDate shouldBe LocalDate.of(2026, 10, 31)
        halloween.startTime.shouldBeNull()
        halloween.endDate.shouldBeNull()

        val stomp = first("Summer Stomp")
        stomp.eventDate shouldBe LocalDate.of(2027, 8, 27)
        stomp.endDate shouldBe LocalDate.of(2027, 8, 28)
        stomp.endTime.shouldBeNull()
    }

    @Test
    fun `bills the named acts of a band night but not the bare word band`() {
        first("Muffcase / Bands").artists shouldContainExactly listOf(ScrapedArtist("Muffcase"))
        first("Moi moi+ bands").artists shouldContainExactly listOf(ScrapedArtist("Moi moi"))
        first("Stone cold love + band").artists shouldContainExactly listOf(ScrapedArtist("Stone cold love"))
        first("Open Jam/ Open Mic").artists.shouldBeEmpty()
        first("Weihnachten vergessen").artists.shouldBeEmpty()
    }

    @Test
    fun `classifies titles outside the fixture`() {
        programmeType("July15th + Scavenger + Becoming Wind", null) shouldBe "CONCERT"
        programmeType("Antisemitismus im Punk Lesung", null) shouldBe "READING"
        programmeType("Workshop: Parteien und ihre Positionen", null).shouldBeNull()
        programmeType("FestiWahl", null).shouldBeNull()
        programmeType("Küfa", "Vegan und lecker\n1-2€ pro Portion").shouldBeNull()
    }

    @Test
    fun `reads an empty calendar as no events`() {
        CafeKoepenickCalendarScraper().scrape("BEGIN:VCALENDAR\nVERSION:2.0\nEND:VCALENDAR\n", feedUrl).shouldBeEmpty()
    }
}
