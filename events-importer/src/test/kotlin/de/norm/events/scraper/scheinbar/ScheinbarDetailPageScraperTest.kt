package de.norm.events.scraper.scheinbar

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class ScheinbarDetailPageScraperTest {
    private val scraper = ScheinbarDetailPageScraper()

    private fun scrape(name: String) =
        scraper.scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/scheinbar/$name")!!
                    .bufferedReader()
                    .readText(),
                "https://www.scheinbar.de/programm/"
            )
        )

    @Test
    fun `reads the acts after Mit dabei in page order`() {
        val programme = scrape("scheinbar-programm-stage-diven.html")

        programme.artists.map { it.name } shouldContainExactly
            listOf("Allison Wonder", "Katrina Martinez", "Hoop Boom Boom", "Madlen & Rang De", "Katharina Greve", "Bartuschka")
        programme.description.shouldNotBeNull() shouldContain "Mit dabei:"
    }

    @Test
    fun `adds no act for a page without a Mit dabei list`() {
        scrape("scheinbar-programm-detail.html").artists.shouldBeEmpty()
    }

    @Test
    fun `stops at the first line that names no discipline`() {
        val html =
            """
            <article class="progFloat"><p>Mit dabei:<br>Anna Bolika | Jonglage<br>Freier Eintritt für Kinder<br>Not An Act | Zaubern</p></article>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html)).artists.map { it.name } shouldContainExactly listOf("Anna Bolika")
    }

    @Test
    fun `reads a list split over paragraphs`() {
        val html =
            """
            <article class="progFloat"><p>Mit dabei:</p><p>Anna Bolika | Jonglage</p><p>Ben Hur | Comedy</p></article>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html)).artists.map { it.name } shouldContainExactly listOf("Anna Bolika", "Ben Hur")
    }
}
