package de.norm.events.scraper.hausderstatistik

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.stubFetcher
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class HausDerStatistikWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = HausDerStatistikWebsiteImporter(htmlFetcher)
    private val entryUrl = "https://hausderstatistik.org/programm/"
    private val months = listOf("2026-10", "2026-11", "2026-12", "2027-01", "2027-02", "2027-03", "2027-04")
    private val concertUrl = "https://hausderstatistik.org/event/lovetempo-nyc-mattie-safer-of-the-rapture-moriah-plaza/"
    private val talkUrl = "https://hausderstatistik.org/event/matthias-mollner-fighters-in-the-crash-zone/"

    private fun monthUrl(month: String) = "$entryUrl?typ=veranstaltung&monat%5B%5D=$month"

    @BeforeEach
    fun setUp() {
        months.drop(1).forEach { month ->
            coEvery { htmlFetcher.fetchDocument(monthUrl(month)) } returns Jsoup.parse("<div class='listing'></div>", monthUrl(month))
        }
        stubFetcher(
            entryUrl to "hausderstatistik/hausderstatistik-entry.html",
            monthUrl("2026-10") to "hausderstatistik/hausderstatistik-2026-10.html",
            concertUrl to "hausderstatistik/hausderstatistik-event-lovetempo.html",
            fetcher = htmlFetcher
        )
        coEvery { htmlFetcher.fetchDocument(talkUrl) } throws HttpFetchException(404, talkUrl)
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.HAUS_DER_STATISTIK
    }

    @Test
    fun `reads the events of every month the filter offers and each event's own page`() =
        runTest {
            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 2
            result.etag.shouldBeNull()
            result.complete shouldBe true
            months.forEach { coVerify(exactly = 1) { htmlFetcher.fetchDocument(monthUrl(it)) } }
            val concert = result.events.single { it.sourceUrl == concertUrl }
            concert.startTime shouldBe LocalTime.parse("20:00")
            concert.detailUnavailable shouldBe false
            result.events.single { it.sourceUrl == talkUrl }.detailUnavailable shouldBe true
        }

    @Test
    fun `keeps the other months when one fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(monthUrl("2026-11")) } throws HttpFetchException(503, monthUrl("2026-11"))

            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 2
            result.complete shouldBe false
        }

    @Test
    fun `folds the open days of an exhibition into one run, as events 12925 to 12930`() =
        runTest {
            val base = "https://hausderstatistik.org/event/"
            val rows =
                listOf(
                    listOf("2026-11-09T18:00:00+01:00", "exhibition-opening-webbing", "Exhibition Opening: Webbing, Weaving by A", "Ausstellung"),
                    listOf("2026-11-11T16:00:00+01:00", "exhibition-is-open-webbing-2", "Exhibition is open! Webbing, Weaving, by A", ""),
                    listOf("2026-11-14T16:00:00+01:00", "exhibition-is-open-webbing-5", "Exhibition is open! Webbing, Weaving, by A", ""),
                    listOf("2026-11-14T17:00:00+01:00", "abend", "Abend", "")
                )
            val listing =
                rows.joinToString("", "<div class='listing'>", "</div>") { row ->
                    "<div class='teaser event'><time datetime='${row[0]}'></time><a href='$base${row[1]}/' rel='bookmark'>" +
                        "<div class='teaser__header'><p class='title'>${row[2]}</p></div></a>" +
                        "<div class='button-group'><a class='button'>${row[3]}</a></div></div>"
                }
            coEvery { htmlFetcher.fetchDocument(monthUrl("2026-11")) } returns Jsoup.parse(listing, monthUrl("2026-11"))
            rows.forEach { row -> coEvery { htmlFetcher.fetchDocument("$base${row[1]}/") } throws HttpFetchException(404, "$base${row[1]}/") }

            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            val run = result.events.single { it.sourceId == "haus_der_statistik:exhibition-webbing-weaving-by-a" }
            run.title shouldBe "Webbing, Weaving by A"
            run.eventType shouldBe EventType.EXHIBITION.name
            run.eventDate shouldBe LocalDate.parse("2026-11-09")
            run.endDate shouldBe LocalDate.parse("2026-11-14")
            run.endTime.shouldBeNull()
            run.startTime shouldBe LocalTime.parse("18:00")
            result.events.single { it.sourceId == "haus_der_statistik:abend" }.title shouldBe "Abend"
            result.events shouldHaveSize 4
        }

    @Test
    fun `returns no events when the entry page offers no month`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns Jsoup.parse("<form><input name='monat[]' value='0'></form>", entryUrl)

            importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>().events shouldHaveSize 0
        }
}
