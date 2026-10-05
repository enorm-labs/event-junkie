package de.norm.events.scraper.zigzag

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalTime

/** Unit tests for [ZigZagDetailPageScraper]. */
class ZigZagDetailPageScraperTest {
    private val scraper = ZigZagDetailPageScraper()

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
    fun `reads a hall page, whose location is an image`() {
        val detail = page("zigzag-hall-detail.html")

        detail.startTime shouldBe LocalTime.of(20, 0)
        detail.doorsTime shouldBe LocalTime.of(19, 0)
        detail.price shouldBe BigDecimal("35")
        detail.ticketUrl shouldBe "https://www.eventim-light.com/de/a/665710997ed5f05a0e32f0e9/e/6a9a94a74d592f671999091d"
        detail.elsewhere shouldBe true
        detail.description!! shouldStartWith "Seit seinem eindrucksvollen Auftauchen"
    }

    @Test
    fun `bills a tribute night's line-up by name, without instrument or country`() {
        val detail = page("zigzag-detail-tribute.html")

        detail.artists.map { it.name } shouldBe listOf("Mette Nadja Hansen", "Eldar Tsalikov", "Declan Forde", "James Banner", "Ugo Alluni")
        detail.artists.map { it.role }.toSet() shouldBe setOf("HEADLINER")
    }

    @Test
    fun `reads an en-dash line-up and one broken by line breaks`() {
        page("zigzag-detail.html").artists.map { it.name } shouldBe
            listOf("Roland Satterwhite", "David Preston", "Olivia Trummer", "Makar Novikov", "Ivars Arutyunyan")
        page("zigzag-detail-jam.html").artists.map { it.name } shouldBe listOf("URI GINCEL", "PAUL KLEBER", "TOBIAS BACKHAUS")
    }

    @Test
    fun `reads a line-up without countries and with a dash against the name`() {
        val detail =
            scraper.scrape(
                Jsoup.parse(
                    """
                    <div class="eventitem-column-content">
                      <h3>JOHANN GIESECKE- Trombone&nbsp;</h3>
                      <h3>Leander Neidig - keys / MusiCAL DIRECTOR</h3>
                      <p>Freuen Sie sich auf einen Abend - mit Soul, Funk und Jazz!</p>
                      <p>(for English please scroll down)</p>
                      <p>Max Kietov - bass</p>
                    </div>
                    """.trimIndent()
                )
            )

        detail.artists.map { it.name } shouldBe listOf("JOHANN GIESECKE", "Leander Neidig")
    }

    @Test
    fun `bills no one from a line-up without a dash`() {
        page("zigzag-hall-detail.html").artists.shouldBeEmpty()
    }

    @Test
    fun `returns an empty detail for a page without a body`() {
        val detail = scraper.scrape(Jsoup.parse("<html><body></body></html>"))

        detail.description.shouldBeNull()
        detail.startTime.shouldBeNull()
        detail.price.shouldBeNull()
        detail.elsewhere shouldBe false
    }

    // The page says "(for English please scroll down)" and nothing where the English starts (#330).
    @Test
    fun `cuts the blurb where it turns English and drops the pointer`() {
        val detail = page("zigzag-detail.html")

        val german = detail.description.shouldNotBeNull()
        german shouldStartWith "Das Roland Satterwhite Quartet wurde 2025 gegründet"
        german shouldNotContain "scroll down"
        german shouldNotContain "The Roland Satterwhite Quartet"
        detail.descriptionAlt.shouldNotBeNull() shouldStartWith "The Roland Satterwhite Quartet was founded in 2025"
    }

    // "Nicht verpassen!" and the first English line are too short to call; each goes with the language it leans to.
    @Test
    fun `gives the lines too short to call to the half they lean to, and the session host to both`() {
        val detail = page("zigzag-detail-jam.html")

        detail.description.shouldNotBeNull() shouldEndWith "Nicht verpassen!"
        detail.descriptionAlt.shouldNotBeNull() shouldStartWith "Session Host: Uri Gincel\nHippest session in town!!\nLike every Tuesday"
    }
}
