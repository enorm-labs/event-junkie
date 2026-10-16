package de.norm.events.scraper.hausderstatistik

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalTime

class HausDerStatistikWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = HausDerStatistikWebsiteImporter(htmlFetcher)
    private val entryUrl = "https://hausderstatistik.org/programm/"
    private val months = listOf("2026-10", "2026-11", "2026-12", "2027-01", "2027-02", "2027-03", "2027-04")
    private val concertUrl = "https://hausderstatistik.org/event/lovetempo-nyc-mattie-safer-of-the-rapture-moriah-plaza/"
    private val talkUrl = "https://hausderstatistik.org/event/matthias-mollner-fighters-in-the-crash-zone/"

    private fun monthUrl(month: String) = "$entryUrl?typ=veranstaltung&monat%5B%5D=$month"

    private fun fixture(
        name: String,
        url: String
    ): Document =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/hausderstatistik/$name")!!
                .bufferedReader()
                .readText(),
            url
        )

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetchDocument(entryUrl) } returns fixture("hausderstatistik-entry.html", entryUrl)
        months.drop(1).forEach { month ->
            coEvery { htmlFetcher.fetchDocument(monthUrl(month)) } returns Jsoup.parse("<div class='listing'></div>", monthUrl(month))
        }
        coEvery { htmlFetcher.fetchDocument(monthUrl("2026-10")) } returns fixture("hausderstatistik-2026-10.html", monthUrl("2026-10"))
        coEvery { htmlFetcher.fetchDocument(concertUrl) } returns fixture("hausderstatistik-event-lovetempo.html", concertUrl)
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
    fun `returns no events when the entry page offers no month`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns Jsoup.parse("<form><input name='monat[]' value='0'></form>", entryUrl)

            importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>().events shouldHaveSize 0
        }
}
