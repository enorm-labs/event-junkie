package de.norm.events.scraper.acudmachtneu

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class AcudMachtNeuWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = AcudMachtNeuWebsiteImporter(htmlFetcher)
    private val url = "https://acudmachtneu.de/programm/"
    private val nextMonthUrl = "https://acudmachtneu.de/programm/2026/11/"
    private val burreUrl = "https://acudmachtneu.de/events/2390/burre-w-heavenphetamine"

    @Test
    fun `reads this month and the next but no further, merges each event page and flags the ones that fail`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any()) } returns
                FetchResult.Success(document = document("acudmachtneu-overview.html", url), etag = "\"a1\"", lastModified = null)
            coEvery { htmlFetcher.fetchDocument(any()) } throws HttpFetchException(503, "https://acudmachtneu.de/")
            coEvery { htmlFetcher.fetchDocument(nextMonthUrl) } returns document("acudmachtneu-month-empty.html", nextMonthUrl)
            coEvery { htmlFetcher.fetchDocument(burreUrl) } returns document("acudmachtneu-event-burre.html", burreUrl)

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"a1\""
            result.complete shouldBe true
            result.events shouldHaveSize 15
            result.events.count { it.detailUnavailable } shouldBe 14
            val burre = result.events.single { it.sourceUrl == burreUrl }
            burre.eventDate shouldBe LocalDate.of(2026, 10, 16)
            burre.startTime shouldBe LocalTime.of(20, 0)
            burre.room shouldBe "Club"
            burre.imageUrl shouldBe "https://acudmachtneu.de/resources/uploads/2026/10/blurred-frame-3.jpg"
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(nextMonthUrl) }
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(url).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `handles an empty listing`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any()) } returns
                FetchResult.Success(document = Jsoup.parse("<html><body></body></html>", url), etag = null, lastModified = null)

            (importer.importEvents(url) as ImportResult.Success).events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.ACUD_MACHT_NEU
        importer.fetchesBeyondEntryPage shouldBe true
    }

    private fun document(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/acudmachtneu/$name")!!
            .bufferedReader()
            .readText(),
        url
    )
}
