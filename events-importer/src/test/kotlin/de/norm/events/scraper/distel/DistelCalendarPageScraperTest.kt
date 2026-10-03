package de.norm.events.scraper.distel

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class DistelCalendarPageScraperTest {
    private val scraper = DistelCalendarPageScraper()
    private val url = "https://distel-berlin.de/spielplan/kalender/"

    private fun document(name: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/distel/$name")!!
                .bufferedReader()
                .readText(),
            url
        )

    @Test
    fun `reads every performance of the month`() {
        scraper.scrape(document("distel-kalender.html"), url) shouldHaveSize 32
    }

    @Test
    fun `reads a performance with its shop id as identity`() {
        val first = scraper.scrape(document("distel-kalender.html"), url).first()

        first.title shouldBe "Die Rückkehr der Späti-Ritter"
        first.subtitle shouldBe "Kiosk. Krise. Kanzlerschaft."
        first.eventType shouldBe "COMEDY"
        first.eventDate shouldBe LocalDate.of(2026, 10, 3)
        first.startTime shouldBe LocalTime.of(15, 0)
        first.sourceUrl shouldBe "https://distel-berlin.de/spielplan/event/die-rueckkehr-der-spaeti-ritter/"
        first.sourceId shouldBe "distel:die-rueckkehr-der-spaeti-ritter-2026-10-03-1500"
        first.ticketUrl shouldBe "https://shop.distel-berlin.de/webshop/webticket/shop?event=12210"
        first.soldOut shouldBe false
    }

    @Test
    fun `marks a performance with no seat left as sold out`() {
        scraper.scrape(document("distel-kalender.html"), url).count { it.soldOut } shouldBe 1
    }

    @Test
    fun `reads a later month and stops at an empty one`() {
        val february = scraper.scrape(document("distel-kalender-202702.html"), url)

        february shouldHaveSize 2
        february.first().ticketUrl shouldBe null
        february.all { it.eventDate.year == 2027 && it.eventDate.monthValue == 2 } shouldBe true
        scraper.isEmptyMonth(document("distel-kalender-202703-empty.html")) shouldBe true
        scraper.scrape(document("distel-kalender-202703-empty.html"), url).shouldBeEmpty()
    }
}
