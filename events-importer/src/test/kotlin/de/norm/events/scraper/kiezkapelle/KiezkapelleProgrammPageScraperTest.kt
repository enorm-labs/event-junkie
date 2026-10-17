package de.norm.events.scraper.kiezkapelle

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [KiezkapelleProgrammPageScraper], against a saved copy of the real programme and rows built on its markup. */
class KiezkapelleProgrammPageScraperTest {
    private val scraper = KiezkapelleProgrammPageScraper()
    private val baseUrl = "https://www.kiezkapelle.de/programm/"

    private fun fixture() =
        javaClass.classLoader
            .getResourceAsStream("scraper/kiezkapelle/kiezkapelle-programm.html")
            .let { requireNotNull(it) }
            .bufferedReader()
            .readText()

    private fun scrape(html: String) = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)

    /** One upcoming entry as the Lay Theme grid lays it out: picture row, date and heading row, text row. */
    private fun entry(
        dateLines: String,
        headings: String,
        text: String = "<p>Ein Abend im Kapellensaal.</p>"
    ) = """
        <div class="row one-col-row"><div class="row-inner"><div class="column-wrap"><div class="col type-img"><div class="img">
          <img class="lay-image-responsive" src="data:image/png;base64,R0lGOD" data-src="https://www.kiezkapelle.de/wp-content/uploads/2026/10/abend.jpg">
        </div></div></div></div></div>
        <div class="row"><div class="row-inner"><div class="column-wrap">
          <div class="col type-text"><div class="text lay-textformat-parent">$dateLines</div></div>
          <div class="col type-text"><div class="text lay-textformat-parent">$headings</div></div>
        </div></div></div>
        <div class="row one-col-row"><div class="row-inner"><div class="column-wrap"><div class="col type-text">
          <div class="text lay-textformat-parent">$text</div>
        </div></div></div></div>
    """

    private fun page(vararg entries: String) =
        """
        <html><body><div class="grid">
        ${entries.joinToString("\n")}
        <div class="row one-col-row"><div class="row-inner"><div class="column-wrap"><div class="col type-text">
          <div class="text lay-textformat-parent"><p>Veranstaltungen aus der Vergangenheit</p></div>
        </div></div></div></div>
        ${entry("<p>Freitag</p><p>24. Januar 2025</p><p>20:00 Uhr</p>", "<h1>Jubiläumskonzert</h1>")}
        </div></body></html>
        """

    private val concert =
        entry(
            "<p>Freitag</p><p>13. November 2026</p><p>20:00 - 22:00 Uhr</p>",
            "<h1><span>Perperúna präsentiert: </span></h1><h1><span>Polyphone Lieder aus dem Balkan</span></h1>",
            "<p>Ein Konzert mit sieben Stimmen.</p><p>Eintritt auf Spendenbasis.</p>"
        )

    @Test
    fun `the live programme holds two walks and nothing in scope`() {
        scrape(fixture()).shouldBeEmpty()
    }

    @Test
    fun `scrape maps an upcoming entry with every field the row carries`() {
        val event = scrape(page(concert)).single()

        event.title shouldBe "Polyphone Lieder aus dem Balkan"
        event.eventDate shouldBe LocalDate.of(2026, 11, 13)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.endTime shouldBe LocalTime.of(22, 0)
        event.doorsTime.shouldBeNull()
        event.promoters shouldContainExactly listOf("Perperúna")
        event.description shouldBe "Ein Konzert mit sieben Stimmen.\n\nEintritt auf Spendenbasis."
        event.imageUrl shouldBe "https://www.kiezkapelle.de/wp-content/uploads/2026/10/abend.jpg"
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "kiezkapelle:2026-11-13-polyphone-lieder-aus-dem-balkan"
    }

    @Test
    fun `the archive below the past-events heading is never read`() {
        scrape(page()).shouldBeEmpty()
    }

    @Test
    fun `walks, workshops, yoga and an off-site meeting point are out of scope`() {
        val walk = entry("<p>Samstag</p><p>17. Oktober 2026</p><p>15:00 - 17:00 Uhr</p>", "<h1>Grief Walk | Ein kontemplativer Spaziergang</h1>")
        val workshop = entry("<p>Montag</p><p>16. November 2026</p><p>18:00 Uhr</p>", "<h1>Workshop Siebdruck</h1>")
        val yoga = entry("<p>Dienstag</p><p>17. November 2026</p><p>18:00 Uhr</p>", "<h1>Yoga im Saal</h1>")
        val offSite =
            entry(
                "<p>Sonntag</p><p>22. November 2026</p><p>10:00 Uhr</p><p><em>Treffpunkt Gartencafé</em></p>",
                "<h1>Ecological Grief</h1>"
            )

        scrape(page(walk, workshop, yoga, offSite, concert)).map { it.title } shouldContainExactly listOf("Polyphone Lieder aus dem Balkan")
    }

    @Test
    fun `an entry without a time range keeps its date and no times`() {
        val reading = entry("<p>Donnerstag</p><p>3. Dezember 2026</p>", "<h1>Lesung: Den Abschied beim Wort nehmen</h1>", "<p></p>")

        val event = scrape(page(reading)).single()

        event.eventDate shouldBe LocalDate.of(2026, 12, 3)
        event.startTime.shouldBeNull()
        event.endTime.shouldBeNull()
        event.promoters.shouldBeEmpty()
        event.description.shouldBeNull()
    }

    @Test
    fun `a page without a programme yields nothing`() {
        scrape("<html><body><p>Wartungsarbeiten</p></body></html>").shouldBeEmpty()
    }
}
