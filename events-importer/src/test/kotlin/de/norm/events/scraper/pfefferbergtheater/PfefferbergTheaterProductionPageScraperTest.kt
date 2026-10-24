package de.norm.events.scraper.pfefferbergtheater

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class PfefferbergTheaterProductionPageScraperTest {
    private val url = "https://pfefferberg-theater.de/spielplan/kalender/lesung-fil-01/"

    private val production by lazy {
        PfefferbergTheaterProductionPageScraper().scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/pfefferbergtheater/pfefferbergtheater-production.html")!!
                    .bufferedReader()
                    .readText(),
                url
            )
        )
    }

    @Test
    fun `reads the whole text from the page, not the JSON-LD's cut`() {
        val description = production.description.shouldNotBeNull()
        description shouldStartWith "Ausverkauft! Zusatztermin am 16.12."
        description shouldContain "Fil wuchs im Märkischen Viertel auf"
        description shouldNotContain "Rollstuhlfahrer"
    }

    private fun audienceOf(fixture: String) =
        audienceOfPage(
            javaClass.classLoader
                .getResourceAsStream("scraper/pfefferbergtheater/$fixture")!!
                .bufferedReader()
                .readText()
        )

    private fun audienceOfPage(html: String) = PfefferbergTheaterProductionPageScraper().scrape(Jsoup.parse(html, url)).childrensAudience

    @Test
    fun `reads a family or school show's audience line and no other`() {
        audienceOf("pfefferbergtheater-maerchen-family.html") shouldBe "Märchen für ALLE – Zutritt ab 4 Jahren"
        audienceOf("pfefferbergtheater-maerchen-school.html") shouldBe "Schulvorstellung ab 4 Jahren"
        audienceOf("pfefferbergtheater-maerchen-adult.html") shouldBe null
        production.childrensAudience shouldBe null
    }

    @Test
    fun `leaves an adults-only show for twelve-year-olds unmarked`() {
        audienceOfPage("""<main><div class="richtext"><p><em>Nur für Erwachsene – Zutritt ab 12 Jahren</em></p></div></main>""") shouldBe null
    }

    @Test
    fun `reads the image and the ticket link`() {
        production.imageUrl shouldBe "https://pfefferberg-theater.de/site/assets/files/29934/fil.800x600n.webp"
        production.ticketUrl.shouldNotBeNull() shouldStartWith "https://pfefferberg.billeto.net/"
    }
}
