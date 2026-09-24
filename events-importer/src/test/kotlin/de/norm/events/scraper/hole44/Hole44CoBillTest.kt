package de.norm.events.scraper.hole44

import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/**
 * The detail page's own description decides a comma the title cannot (#1832): Hole 44 bills
 * `Myki, Darlene & Nini` and describes `Myki Meeks, Darlene Mitchell, and Nini Coco`, so the acts
 * are billed under names only the blurb carries.
 */
class Hole44CoBillTest {
    private val scraper = Hole44DetailPageScraper()
    private val sourceUrl = "https://hole-berlin.de/event/2026-10-07-myki-darlene-nini/"

    private fun readFixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/hole44/$name")!!
            .bufferedReader()
            .readText()

    @Test
    fun `bills the acts the description names in full`() {
        val event = scraper.scrape(Jsoup.parse(readFixture("hole44-detail-cobill.html"), sourceUrl), sourceUrl)!!
        event.title shouldBe "Myki, Darlene & Nini"
        event.artists.map { it.name } shouldBe listOf("Myki Meeks", "Darlene Mitchell", "Nini Coco")
    }
}
