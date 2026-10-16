package de.norm.events.scraper.hausderstatistik

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class HausDerStatistikMonthPageScraperTest {
    private val scraper = HausDerStatistikMonthPageScraper()
    private val monthUrl = "https://hausderstatistik.org/programm/?typ=veranstaltung&monat%5B%5D=2026-10"

    private fun fixture(name: String): Document =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/hausderstatistik/$name")!!
                .bufferedReader()
                .readText(),
            monthUrl
        )

    @Test
    fun `reads the concert and the talk and drops the weekly get-together`() {
        val events = scraper.scrape(fixture("hausderstatistik-2026-10.html"), monthUrl)

        events.map { it.title } shouldContainExactly
            listOf("Matthias Mollner – „Fighters in the Crash Zone“", "lovetempo (NYC / Mattie Safer of The Rapture) + Moriah Plaza")
    }

    @Test
    fun `reads every field of a concert row`() {
        val concert = scraper.scrape(fixture("hausderstatistik-2026-10.html"), monthUrl).single { it.title.startsWith("lovetempo") }

        concert.eventType shouldBe EventType.CONCERT.name
        concert.eventDate shouldBe LocalDate.parse("2026-10-23")
        concert.startTime shouldBe LocalTime.parse("19:00")
        concert.endDate shouldBe LocalDate.parse("2026-10-23")
        concert.endTime shouldBe LocalTime.parse("23:00")
        concert.sourceUrl shouldBe "https://hausderstatistik.org/event/lovetempo-nyc-mattie-safer-of-the-rapture-moriah-plaza/"
        concert.sourceId shouldBe "haus_der_statistik:lovetempo-nyc-mattie-safer-of-the-rapture-moriah-plaza"
        concert.artists.map { it.name } shouldContainExactly listOf("lovetempo", "Moriah Plaza")
    }

    @Test
    fun `types a talk as a reading without acts`() {
        val talk = scraper.scrape(fixture("hausderstatistik-2026-10.html"), monthUrl).single { it.title.startsWith("Matthias Mollner") }

        talk.eventType shouldBe EventType.READING.name
        talk.description shouldBe
            "Buchvorstellung mit Vortrag, Kunst und Podiumsdiskussion zu ME/CFS und komplexer chronischer Krankheit. In Anwesenheit des Autors und Künstlers."
        talk.artists.shouldBeEmpty()
    }

    @Test
    fun `drops workshops, meetings and cooking from the whole programme`() {
        val titles = scraper.scrape(fixture("hausderstatistik-entry.html"), monthUrl).map { it.title }

        titles.none { it.contains("Werkstatt", ignoreCase = true) || it.contains("Gartenarbeit") || it.contains("Potluck") } shouldBe true
        titles.none { it == "PreciousPlasticBerlin" || it == "Open Burner Embassy" } shouldBe true
    }

    @Test
    fun `keeps a row without tags and skips one without a date`() {
        val html =
            """
            <div class="listing">
              <div class="teaser event"><time datetime="2026-11-06T20:00:00+01:00">06.11.</time>
                <a href="https://hausderstatistik.org/event/abend/" rel="bookmark"><div class="teaser__header"><p class="title is-4">Abend</p></div></a></div>
              <div class="teaser event"><a href="https://hausderstatistik.org/event/ohne/" rel="bookmark"><div class="teaser__header"><p class="title is-4">Ohne</p></div></a></div>
            </div>
            """.trimIndent()

        val events = scraper.scrape(Jsoup.parse(html, monthUrl), monthUrl)

        events.map { it.sourceId } shouldContainExactly listOf("haus_der_statistik:abend")
        events.single().eventType shouldBe null
    }

    @Test
    fun `calls a row participation only when every tag names a participation format`() {
        HausDerStatistikMonthPageScraper.isParticipationFormat(listOf("Werkstatt", "Workshop")) shouldBe true
        HausDerStatistikMonthPageScraper.isParticipationFormat(listOf("Diskussion")) shouldBe true
        HausDerStatistikMonthPageScraper.isParticipationFormat(listOf("Diskussion", "Vortrag")) shouldBe false
        HausDerStatistikMonthPageScraper.isParticipationFormat(listOf("Workshop", "Musik")) shouldBe false
        HausDerStatistikMonthPageScraper.isParticipationFormat(emptyList()) shouldBe false
    }
}
