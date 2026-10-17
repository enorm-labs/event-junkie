package de.norm.events.scraper.renaissancetheater

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class RenaissanceTheaterProductionPageScraperTest {
    private val scraper = RenaissanceTheaterProductionPageScraper()

    private fun scrape(
        name: String,
        url: String
    ) = scraper.scrape(
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/renaissancetheater/$name")!!
                .bufferedReader()
                .readText(),
            url
        )
    )

    @Test
    fun `reads the text with its folded part and the hero image`() {
        val production =
            scrape("renaissancetheater-von-fischen-und-frauen.html", "https://renaissance-theater.de/produktion/von-fischen-und-frauen/").shouldNotBeNull()

        val description = production.description.shouldNotBeNull()
        description shouldStartWith "Ein Idyll – das gemeinsame Warten auf den Fisch. Es ist früh am Morgen."
        description shouldEndWith "(Statement der Hans-Gratzer-Jury)"
        description shouldNotContain "Mehr Lesen"
        description shouldNotContain "Spieldauer"
        description shouldNotContain "YouTube"
        production.imageUrl shouldBe "https://renaissance-theater.de/wp-content/uploads/Website_Hero-Bild_FuF-1-scaled.jpg"
    }

    @Test
    fun `stops the text before the accordion`() {
        val production =
            scrape(
                "renaissancetheater-christian-brueckner-liest.html",
                "https://renaissance-theater.de/produktion/christian-brueckner-liest-truman-capote-erzaehlung/"
            ).shouldNotBeNull()

        val description = production.description.shouldNotBeNull()
        description shouldStartWith "Zwei Porträts, zwei Ikonen des 20. Jahrhunderts."
        description shouldNotContain "Über die Lesereihe"
        description shouldNotContain "Wenn Christian Brückner liest"
    }

    @Test
    fun `returns null for a page without the production column`() {
        scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>")).shouldBeNull()
    }
}
