package de.norm.events.scraper.hafenbar

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
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

/** Unit tests for [HafenbarWebsiteImporter]. */
class HafenbarWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val importer = HafenbarWebsiteImporter(htmlFetcher)

    private val homeUrl = "https://www.hafenbar-berlin.de/"
    private val fridayUrl = "https://www.hafenbar-berlin.de/schlager-party-berlin/"
    private val saturdayUrl = "https://www.hafenbar-berlin.de/kaptains-club-party-berlin/"

    private fun document(
        fixture: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/hafenbar/$fixture")!!
            .bufferedReader()
            .readText(),
        url
    )

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetchDocument(fridayUrl) } returns document("hafenbar-freitag.html", fridayUrl)
        coEvery { htmlFetcher.fetchDocument(saturdayUrl) } returns document("hafenbar-samstag.html", saturdayUrl)
    }

    @Test
    fun `importEvents reads the next night from each weekly party page`() =
        runTest {
            val result = importer.importEvents(homeUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.map { it.sourceId } shouldContainExactly
                listOf("hafenbar:schlager-party-berlin-2026-10-16", "hafenbar:kaptains-club-party-berlin-2026-10-17")
            result.complete shouldBe true
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(homeUrl) }
        }

    @Test
    fun `importEvents keeps the other page and marks the run incomplete when one page fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(fridayUrl) } throws HttpFetchException(500, fridayUrl)

            val result = importer.importEvents(homeUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.map { it.sourceId } shouldContainExactly listOf("hafenbar:kaptains-club-party-berlin-2026-10-17")
            result.complete shouldBe false
        }

    @Test
    fun `importEvents fails when no page can be read`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(fridayUrl) } throws HttpFetchException(500, fridayUrl)
            coEvery { htmlFetcher.fetchDocument(saturdayUrl) } throws HttpFetchException(500, saturdayUrl)

            shouldThrow<IllegalStateException> { importer.importEvents(homeUrl) }
        }

    @Test
    fun `importEvents returns no events for pages without an announcement`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(any()) } returns Jsoup.parse("<html><body></body></html>", homeUrl)

            val result = importer.importEvents(homeUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
            result.complete shouldBe true
        }

    @Test
    fun `the two pages are the whole programme and are fetched unconditionally`() {
        importer.listsWholeProgramme shouldBe true
        importer.fetchesBeyondEntryPage shouldBe true
    }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.HAFENBAR
    }
}
