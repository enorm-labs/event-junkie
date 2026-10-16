package de.norm.events.scraper.marmorbar

import de.norm.events.scraper.EventSource
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Unit tests for [MarmorbarDetailPageScraper], against two event pages captured on 2026-10-16: a night with two Wix
 * ticket tiers, and one sold only at the door.
 */
class MarmorbarDetailPageScraperTest {
    private val scraper = MarmorbarDetailPageScraper()

    private fun scrape(fixture: String) =
        scraper.scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/marmorbar/$fixture")!!
                    .bufferedReader()
                    .readText(),
                URL
            ),
            URL
        )

    @Test
    fun `takes the lowest tier's own price, without the Wix fee, and notes the range`() {
        val event = scrape("marmorbar-detail-kylie.html").shouldNotBeNull()

        event.pricePresale shouldBe BigDecimal("5.00")
        event.priceNote shouldBe "€5.00 – €8.00"
        event.priceBoxOffice.shouldBeNull()
        event.free shouldBe false
        event.eventDate shouldBe LocalDate.of(2026, 11, 6)
        event.sourceId shouldBe "${EventSource.MARMORBAR.sourceIdPrefix}5be04042-923a-4891-bacd-fa876ff347b7"
    }

    @Test
    fun `reads the full text paragraph by paragraph`() {
        val description = scrape("marmorbar-detail-kylie.html").shouldNotBeNull().description.shouldNotBeNull()

        description shouldStartWith "After multiple sold out shows around the UK"
        description shouldContain "\n\nThis is Kylie like you’ve never heard her before."
        description shouldContain "closing up will be Femme Bass Mafia graduate and Refuge Worldwide Resident, miszo."
    }

    @Test
    fun `a night sold only at the door takes its door price from the text`() {
        val event = scrape("marmorbar-detail-flur.html").shouldNotBeNull()

        event.pricePresale.shouldBeNull()
        event.priceNote.shouldBeNull()
        event.priceBoxOffice shouldBe BigDecimal("18")
        // A styled link splits a sentence into three text runs; the paragraph joins them again.
        event.description.shouldNotBeNull() shouldContain "He has been a regular DJ at LAB.dance and brings a deep connection"
    }

    @Test
    fun `a page without a warmup payload yields nothing`() {
        scraper.scrape(Jsoup.parse("<html><body></body></html>", URL), URL).shouldBeNull()
    }

    private companion object {
        const val URL = "https://www.marmorbar.com/en/event-details/kylie-wears-berghaus"
    }
}
