package de.norm.events.scraper.zigzag

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalTime

/** Unit tests for [ZigZagJazzClubDetailPageScraper]. */
class ZigZagJazzClubDetailPageScraperTest {
    private val scraper = ZigZagJazzClubDetailPageScraper()

    private fun page(name: String) =
        scraper.scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/zigzag/$name")!!
                    .bufferedReader()
                    .readText(),
                "https://www.zigzag-jazzclub.berlin/program-mai/x"
            )
        )

    @Test
    fun `reads times, admission, ticket link and blurb`() {
        val detail = page("zigzag-detail.html")

        detail.startTime shouldBe LocalTime.of(20, 0)
        detail.doorsTime shouldBe LocalTime.of(19, 0)
        detail.price shouldBe BigDecimal("25")
        detail.ticketUrl shouldBe "https://www.eventim-light.com/de/a/665710997ed5f05a0e32f0e9/e/6a9beca7de97322707c097a7"
        detail.elsewhere shouldBe false
        detail.description!! shouldStartWith "Das Roland Satterwhite Quartet wurde 2025 gegründet"
        detail.description shouldNotContain "Tickets"
        detail.description shouldNotContain "scroll down"
    }

    @Test
    fun `reads the jam session's split time and box-office price`() {
        val detail = page("zigzag-detail-jam.html")

        detail.startTime shouldBe LocalTime.of(20, 0)
        detail.doorsTime shouldBe LocalTime.of(19, 0)
        detail.price shouldBe BigDecimal("20")
        detail.ticketUrl.shouldBeNull()
    }

    @Test
    fun `flags a concert at another location and keeps the first of two shows`() {
        val detail = page("zigzag-detail-elsewhere.html")

        detail.elsewhere shouldBe true
        detail.startTime shouldBe LocalTime.of(18, 0)
        detail.price shouldBe BigDecimal("45")
    }

    @Test
    fun `returns an empty detail for a page without a body`() {
        val detail = scraper.scrape(Jsoup.parse("<html><body></body></html>"))

        detail.description.shouldBeNull()
        detail.startTime.shouldBeNull()
        detail.price.shouldBeNull()
        detail.elsewhere shouldBe false
    }
}
