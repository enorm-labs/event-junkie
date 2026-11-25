package de.norm.events.scraper.clubost

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.stubFetcher
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ClubOstWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = ClubOstWebsiteImporter(htmlFetcher)
    private val url = "https://clubost.de/"

    @Test
    fun `imports the homepage programme with its lineups`() =
        runTest {
            stubFetcher(url to "clubost/clubost-home.html", fetcher = htmlFetcher)

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 13
            result.events.first().artists shouldHaveSize 3
        }

    @Test
    fun `passes NotModified through`() =
        runTest {
            coEvery { htmlFetcher.fetch(url, any(), any(), any()) } returns FetchResult.NotModified

            importer.importEvents(url).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.CLUB_OST
        importer.listsWholeProgramme shouldBe true
    }
}
