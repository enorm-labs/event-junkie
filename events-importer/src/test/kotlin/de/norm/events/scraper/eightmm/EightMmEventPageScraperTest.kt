package de.norm.events.scraper.eightmm

import de.norm.events.scraper.ScrapedEvent
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

    private fun page(name: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/eightmm/$name")!!
                .bufferedReader()
                .readText(),
            "https://www.8mmbar.de/program"
        )

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
        val event = scraper.enrich(listed("RMFC LIVE in 8MM"), page("eightmm-event-rmfc.html"))

        event.description!! shouldStartWith "ROUGH’n’ROLL Concerts presents:\nR.M.F.C. (Sydney, Australia)"
        event.description shouldContain "Aftershow DJ set Liam Carrol"
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.startTime shouldBe LocalTime.of(20, 30)
    }

    @Test
    fun `a page without a blurb leaves the listed event`() {
        val listed = listed("JEFF CLARK (BLACK LIPS) SUNDAY'S IN OCTOBER DJ RESIDENCY")

        val event = scraper.enrich(listed, page("eightmm-event-residency.html"))

        event shouldBe listed
        event.description.shouldBeNull()
    }
}
