package de.norm.events.scraper.ballhausberlin

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class BallhausBerlinWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = BallhausBerlinWebsiteImporter(htmlFetcher)

    @Test
    fun `imports the dates page`() =
        runTest {
            coEvery { htmlFetcher.fetch(BALLHAUS_BERLIN_URL, any(), any(), any()) } returns
                FetchResult.Success(document = ballhausBerlinTermine(), etag = null, lastModified = null)

            val result = importer.importEvents(BALLHAUS_BERLIN_URL)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 77
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.BALLHAUS_BERLIN
        importer.listsWholeProgramme shouldBe true
    }
}
