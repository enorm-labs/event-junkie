package de.norm.events.scraper.karakas

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
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
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Unit tests for [KarakasWebsiteImporter]. */
class KarakasWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val clock = Clock.fixed(ZonedDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneId.of("Europe/Berlin")).toInstant(), ZoneId.of("Europe/Berlin"))
    private val importer = KarakasWebsiteImporter(htmlFetcher, clock)

    private val homeUrl = "https://karakasbar.com/"
    private val comedyUrl = "https://karakasbar.com/comedy-shows"
    private val quizUrl = "https://karakasbar.com/pub-quiz-1"
    private val pageScript = "https://img1.wsimg.com/blobby/go/ad795882-2f3c-4098-9227-326ffaee1e1f/gpub/8a1257d2a3cc8f98/script.js"
    private val siteScript = "https://img1.wsimg.com/blobby/go/ad795882-2f3c-4098-9227-326ffaee1e1f/gpub/ac4d62496081468/script.js"

    private fun fixture(name: String) =
        javaClass.classLoader
            .getResourceAsStream("scraper/karakas/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        coEvery { htmlFetcher.fetchDocument(comedyUrl) } returns Jsoup.parse(fixture("karakas-comedy-shows.html"), comedyUrl)
        coEvery { htmlFetcher.fetchDocument(quizUrl) } returns Jsoup.parse(fixture("karakas-pub-quiz.html"), quizUrl)
        coEvery { htmlFetcher.fetchHtml(pageScript) } returns fixture("karakas-calendar-script.js")
        coEvery { htmlFetcher.fetchHtml(siteScript) } returns "window.wsb={};"
    }

    @Test
    fun `importEvents reads the calendar, the tour cards and the quizzes`() =
        runTest {
            val result = importer.importEvents(homeUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            // 16 calendar nights, 6 tour cards less the one the calendar lists too, 2 quizzes
            result.events shouldHaveSize 23
            result.events.count { it.eventType == "QUIZ" } shouldBe 2
            result.complete shouldBe true
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(homeUrl) }
            coVerify(exactly = 0) { htmlFetcher.fetchHtml(siteScript) }
        }

    @Test
    fun `a tour card for a calendar night is the calendar's entry`() =
        runTest {
            val result = importer.importEvents(homeUrl) as ImportResult.Success

            val katharina = result.events.single { it.eventDate == LocalDate.of(2027, 1, 16) }
            katharina.sourceId shouldBe "karakas:9392eb90-a164-4a18-8f0c-ce7b1e0b0e1d"
        }

    @Test
    fun `importEvents tries the next script when the first carries no calendar`() =
        runTest {
            coEvery { htmlFetcher.fetchHtml(pageScript) } returns "window.wsb={};"
            coEvery { htmlFetcher.fetchHtml(siteScript) } returns fixture("karakas-calendar-script.js")

            val result = importer.importEvents(homeUrl) as ImportResult.Success

            result.events shouldHaveSize 23
        }

    @Test
    fun `a calendar page whose scripts carry no calendar fails, and the quiz page is kept`() =
        runTest {
            coEvery { htmlFetcher.fetchHtml(pageScript) } returns "window.wsb={};"

            val result = importer.importEvents(homeUrl) as ImportResult.Success

            result.events.map { it.eventType }.toSet() shouldBe setOf("QUIZ")
            result.complete shouldBe false
        }

    @Test
    fun `importEvents fails when no page can be read`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(comedyUrl) } throws HttpFetchException(500, comedyUrl)
            coEvery { htmlFetcher.fetchDocument(quizUrl) } throws HttpFetchException(500, quizUrl)

            shouldThrow<IllegalStateException> { importer.importEvents(homeUrl) }
        }

    @Test
    fun `importEvents returns no events for empty pages`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(any()) } returns Jsoup.parse("<html><body></body></html>", homeUrl)

            val result = importer.importEvents(homeUrl) as ImportResult.Success

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
        importer.eventSource shouldBe EventSource.KARAKAS
    }
}
