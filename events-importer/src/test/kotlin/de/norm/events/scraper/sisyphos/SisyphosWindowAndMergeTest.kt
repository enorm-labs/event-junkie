package de.norm.events.scraper.sisyphos

import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class SisyphosWindowAndMergeTest {
    @ParameterizedTest
    @CsvSource(
        "2026-10-02T21:59, false",
        "2026-10-02T22:00, true",
        "2026-10-03T12:00, true",
        "2026-10-04T03:59, true",
        "2026-10-04T04:00, false",
        "2026-09-30T23:00, false",
        "2026-10-05T01:00, false"
    )
    fun `the window is Friday 22 00 to Sunday 04 00 in Berlin`(
        time: String,
        inside: Boolean
    ) {
        inSisyfanWindow(LocalDateTime.parse(time).atZone(BERLIN)) shouldBe inside
    }

    private val weekend =
        ScrapedEvent(
            title = "HAPPY RAVE HAPPY LIFE",
            eventDate = LocalDate.of(2026, 10, 9),
            startTime = LocalTime.of(22, 0),
            endDate = LocalDate.of(2026, 10, 12),
            endTime = LocalTime.of(10, 0),
            sourceUrl = "https://sisy.fan/events/from/09.10.2026/to/12.10.2026",
            lineupSourceUrl = "https://sisy.fan/events/from/09.10.2026/to/12.10.2026",
            sourceId = "sisyphos:2026-10-09",
            artists = listOf(ScrapedArtist(name = "Micha Stahl", stage = "Hammahalle"))
        )

    private val shopNight =
        ScrapedEvent(
            title = "generationS",
            eventDate = LocalDate.of(2026, 10, 10),
            sourceUrl = "https://www.sisyphos-berlin.net/products/generations-10-okt-2026",
            sourceId = "sisyphos:2026-10-10",
            ticketUrl = "https://www.sisyphos-berlin.net/products/generations-10-okt-2026",
            pricePresale = BigDecimal("15.00")
        )

    @Test
    fun `a shop night inside a weekend takes its line-up and end and keeps the rest`() {
        mergeWeekends(listOf(shopNight), listOf(weekend)) shouldBe
            listOf(
                shopNight.copy(
                    artists = weekend.artists,
                    endDate = weekend.endDate,
                    endTime = weekend.endTime,
                    lineupSourceUrl = weekend.lineupSourceUrl
                )
            )
    }

    @Test
    fun `a weekend no shop night claims is an event of its own`() {
        val later = shopNight.copy(eventDate = LocalDate.of(2026, 11, 14), sourceId = "sisyphos:2026-11-14")
        mergeWeekends(listOf(later), listOf(weekend)) shouldBe listOf(later, weekend)
    }

    @Test
    fun `no weekends leaves the shop nights as they are`() {
        mergeWeekends(listOf(shopNight), emptyList()) shouldBe listOf(shopNight)
    }

    private val calendarWeekend =
        ScrapedEvent(
            title = "HAPPY RAVE HAPPY LIFE",
            eventDate = LocalDate.of(2026, 10, 9),
            startTime = LocalTime.of(22, 0),
            endDate = LocalDate.of(2026, 10, 12),
            endTime = LocalTime.of(10, 0),
            sourceUrl = "https://www.sisyphos-berlin.net/",
            sourceId = "sisyphos:2026-10-09"
        )

    @Test
    fun `a timed night takes the weekend's line-up and keeps its own times`() {
        val sets = weekend.copy(startTime = LocalTime.of(23, 0), endDate = LocalDate.of(2026, 10, 11), endTime = LocalTime.of(18, 0))
        mergeWeekends(listOf(calendarWeekend), listOf(sets)) shouldBe
            listOf(calendarWeekend.copy(artists = sets.artists, lineupSourceUrl = sets.lineupSourceUrl))
    }

    @Test
    fun `a Thursday night ending on Friday morning does not take the Friday weekend`() {
        val thursday =
            calendarWeekend.copy(
                title = "generationS",
                eventDate = LocalDate.of(2026, 10, 8),
                startTime = LocalTime.of(18, 0),
                endDate = LocalDate.of(2026, 10, 9),
                endTime = LocalTime.of(3, 0),
                sourceId = "sisyphos:2026-10-08"
            )
        val sets = weekend.copy(eventDate = LocalDate.of(2026, 10, 9), startTime = LocalTime.of(23, 0), sourceId = "sisyphos:2026-10-09")
        mergeWeekends(listOf(thursday), listOf(sets)) shouldBe listOf(thursday, sets)
    }

    @Test
    fun `a weekend with the night's key merges even when its first set is before the opening`() {
        val early = weekend.copy(startTime = LocalTime.of(21, 0))
        mergeWeekends(listOf(calendarWeekend), listOf(early)).single().artists shouldBe early.artists
    }

    private val calendarGenerations =
        ScrapedEvent(
            title = "generationS",
            eventDate = LocalDate.of(2026, 10, 10),
            startTime = LocalTime.of(14, 0),
            endDate = LocalDate.of(2026, 10, 11),
            endTime = LocalTime.of(6, 0),
            sourceUrl = "https://www.sisyphos-berlin.net/",
            sourceId = "sisyphos:2026-10-10",
            ticketUrl = "https://www.sisyphos-berlin.net/products/generations-10-okt-2026"
        )

    @Test
    fun `a shop night joins the calendar night that links its product and keeps the calendar's times`() {
        val shop = shopNight.copy(title = "generationS 10. OKT", soldOut = true)
        joinShop(listOf(calendarGenerations), listOf(shop)) shouldBe
            listOf(calendarGenerations.copy(pricePresale = BigDecimal("15.00"), soldOut = true, ticketUrl = shop.ticketUrl))
    }

    @Test
    fun `a shop night joins by product even when the calendar dates it differently`() {
        val moved = calendarGenerations.copy(eventDate = LocalDate.of(2026, 10, 17), sourceId = "sisyphos:2026-10-17")
        joinShop(listOf(moved), listOf(shopNight)).single().pricePresale shouldBe BigDecimal("15.00")
    }

    @Test
    fun `without a product link a shop night joins by date`() {
        val generic = calendarGenerations.copy(ticketUrl = "https://www.sisyphos-berlin.net/products")
        joinShop(listOf(generic), listOf(shopNight)).single().ticketUrl shouldBe shopNight.ticketUrl
    }

    @Test
    fun `a shop night the calendar does not list stays an event of its own`() {
        val nextYear = shopNight.copy(eventDate = LocalDate.of(2027, 7, 3), sourceId = "sisyphos:2027-07-03", ticketUrl = null)
        joinShop(listOf(calendarGenerations), listOf(nextYear)) shouldBe listOf(calendarGenerations, nextYear)
    }

    @Test
    fun `no shop nights leave the calendar as it is`() {
        joinShop(listOf(calendarGenerations), emptyList()) shouldBe listOf(calendarGenerations)
    }
}
