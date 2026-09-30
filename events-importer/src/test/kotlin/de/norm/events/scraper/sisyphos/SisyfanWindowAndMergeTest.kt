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

class SisyfanWindowAndMergeTest {
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
            sourceId = "sisyphos:weekend-2026-10-09",
            artists = listOf(ScrapedArtist(name = "Micha Stahl", stage = "Hammahalle"))
        )

    private val shopNight =
        ScrapedEvent(
            title = "generationS",
            eventDate = LocalDate.of(2026, 10, 10),
            sourceUrl = "https://www.sisyphos-berlin.net/products/generations-10-okt-2026",
            sourceId = "sisyphos:generations-10-okt-2026",
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
        val later = shopNight.copy(eventDate = LocalDate.of(2026, 11, 14), sourceId = "sisyphos:generations-14-nov-2026")
        mergeWeekends(listOf(later), listOf(weekend)) shouldBe listOf(later, weekend)
    }

    @Test
    fun `no weekends leaves the shop nights as they are`() {
        mergeWeekends(listOf(shopNight), emptyList()) shouldBe listOf(shopNight)
    }
}
