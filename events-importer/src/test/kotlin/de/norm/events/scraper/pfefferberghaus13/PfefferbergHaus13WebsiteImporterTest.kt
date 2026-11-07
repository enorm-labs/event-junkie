package de.norm.events.scraper.pfefferberghaus13

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class PfefferbergHaus13WebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val importer = PfefferbergHaus13WebsiteImporter(htmlFetcher, clock)
    private val listingUrl = "https://haus13.pfefferwerk.de/veranstaltungen/"
    private val page2Url = "https://haus13.pfefferwerk.de/veranstaltungen/page/2/"
    private val event = "https://haus13.pfefferwerk.de/event/"

    private fun doc(
        name: String,
        url: String
    ) = Jsoup.parse(javaClass.classLoader.getResourceAsStream("scraper/pfefferberghaus13/$name")!!, null, url)

    @Test
    fun `reads both listing pages and merges each event page`() =
        runTest {
            coEvery { htmlFetcher.fetch(listingUrl, any(), any(), any()) } returns
                FetchResult.Success(doc("pfefferberghaus13-overview.html", listingUrl), etag = "\"haus13\"", lastModified = null)
            coEvery { htmlFetcher.fetchDocument(page2Url) } returns doc("pfefferberghaus13-overview-page2.html", page2Url)
            coEvery { htmlFetcher.fetchDocument("${event}punk-rocktopus-vol-3/") } returns
                doc("pfefferberghaus13-event-punk-rocktopus.html", "${event}punk-rocktopus-vol-3/")
            coEvery { htmlFetcher.fetchDocument(match { it.startsWith(event) && !it.endsWith("punk-rocktopus-vol-3/") }) } returns
                Jsoup.parse("<html><body></body></html>", listingUrl)

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.size shouldBe 7
            result.etag shouldBe "\"haus13\""
            val punk = result.events.first { it.title == "Punk Rocktopus Vol. 3" }
            punk.doorsTime shouldBe LocalTime.of(19, 0)
            punk.startTime.shouldBeNull()
            punk.artists.map { it.name } shouldBe listOf("Limbo Boys", "Hartholz", "Unglaublicher Vorfall", "AOP")
            result.events.first { it.title.startsWith("Resonanzen") }.startTime shouldBe LocalTime.of(19, 30)
        }

    @Test
    fun `keeps the listing's end date of a multi-day event through the merge`() =
        runTest {
            val liveImporter =
                PfefferbergHaus13WebsiteImporter(htmlFetcher, Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneId.of("Europe/Berlin")))
            val salsaUrl = "${event}vamos-a-bailar-la-fiesta-de-la-salsa-conexion-feat-mayelis-17/"
            coEvery { htmlFetcher.fetch(listingUrl, any(), any(), any()) } returns
                FetchResult.Success(doc("pfefferberghaus13-overview-multi-day.html", listingUrl), etag = null, lastModified = null)
            coEvery { htmlFetcher.fetchDocument(salsaUrl) } returns doc("pfefferberghaus13-event-vamos-a-bailar.html", salsaUrl)
            coEvery { htmlFetcher.fetchDocument(match { it != salsaUrl }) } returns Jsoup.parse("<html><body></body></html>", listingUrl)

            val result = liveImporter.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            val salsa = result.events.single { it.sourceUrl == salsaUrl }
            salsa.eventDate shouldBe LocalDate.of(2026, 11, 21)
            salsa.endDate shouldBe LocalDate.of(2026, 11, 22)
            salsa.doorsTime shouldBe LocalTime.of(19, 30)
            salsa.startTime shouldBe LocalTime.of(20, 0)
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(listingUrl, any(), any(), any()) } returns FetchResult.NotModified

            importer.importEvents(listingUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.PFEFFERBERG_HAUS_13
        importer.fetchesBeyondEntryPage shouldBe true
    }
}
