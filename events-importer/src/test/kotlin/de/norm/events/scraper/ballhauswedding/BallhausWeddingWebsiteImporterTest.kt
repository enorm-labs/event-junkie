package de.norm.events.scraper.ballhauswedding

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

class BallhausWeddingWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val url = "https://www.ballhauswedding.de/veranstaltungen"
    private val clock = Clock.fixed(LocalDate.of(2026, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)
    private val importer = BallhausWeddingWebsiteImporter(htmlFetcher, clock)

    @Test
    fun `reads the programme, then only the entries' own Wix Events pages`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(url) } returns fixture("ballhauswedding-veranstaltungen.html", url)
            coEvery { htmlFetcher.fetchDocument(match { it.contains("/details-registrierung/") }) } throws IllegalStateException("no fixture")
            coEvery { htmlFetcher.fetchDocument(STREISAND) } returns fixture("ballhauswedding-event-barbra-streisand.html", STREISAND)

            val result = importer.importEvents(url, null, null).shouldBeInstanceOf<ImportResult.Success>()

            importer.eventSource shouldBe EventSource.BALLHAUS_WEDDING
            val streisand = result.events.first()
            streisand.imageUrl?.startsWith("https://static.wixstatic.com/") shouldBe true
            streisand.detailUnavailable shouldBe false
            result.events.first { it.title == "Berlin - Odessa - Express" }.detailUnavailable shouldBe true
            result.events.first { it.title.startsWith("Tango") }.detailUnavailable shouldBe false
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(url) }
        }

    private fun fixture(
        name: String,
        baseUrl: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/ballhauswedding/$name")!!
            .bufferedReader()
            .readText(),
        baseUrl
    )

    private companion object {
        const val STREISAND = "https://www.ballhauswedding.de/details-registrierung/legenden-barbara-streisand"
    }
}
