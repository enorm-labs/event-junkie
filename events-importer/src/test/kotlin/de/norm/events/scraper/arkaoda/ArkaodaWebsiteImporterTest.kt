package de.norm.events.scraper.arkaoda

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Unit tests for [ArkaodaWebsiteImporter].
 *
 * Both events on the captured listing have their detail page stubbed, so the tests cover the
 * overview → detail merge as well as the fallback when a detail page cannot be parsed.
 */
class ArkaodaWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private lateinit var importer: ArkaodaWebsiteImporter
    private val sourceUrl = "https://berlin.arkaoda.com/?/default/program"
    private val signalUrl = "https://berlin.arkaoda.com/?/default/detail/id=1320"
    private val mnjmUrl = "https://berlin.arkaoda.com/?/default/detail/id=1321"

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/arkaoda/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = ArkaodaWebsiteImporter(htmlFetcher)

        // The site sends neither ETag nor Last-Modified (Cache-Control: no-store), so
        // the fetch result carries none and every run is an unconditional GET.
        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(fixture("arkaoda-program.html"), sourceUrl),
                etag = null,
                lastModified = null
            )

        coEvery { htmlFetcher.fetchDocument(signalUrl) } returns
            Jsoup.parse(fixture("arkaoda-detail-concert-lineup.html"), signalUrl)
        coEvery { htmlFetcher.fetchDocument(mnjmUrl) } returns
            Jsoup.parse(fixture("arkaoda-detail-party-untyped.html"), mnjmUrl)
    }

    private fun events(result: ImportResult): List<ScrapedEvent> {
        result.shouldBeInstanceOf<ImportResult.Success>()
        return result.events
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.ARKAODA
    }

    @Test
    fun `imports every listed event and reports no cache headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 2
            result.etag shouldBe null
            result.lastModified shouldBe null
        }

    @Test
    fun `enriches a concert with the untruncated detail description`() =
        runTest {
            val signal = events(importer.importEvents(sourceUrl)).first { it.sourceId == "arkaoda:1320" }
            // From the detail page — the listing excerpt stops before this line.
            signal.description.shouldNotBeNull() shouldContain "€10 Entry on the door"
            // Unchanged across the merge.
            signal.title shouldBe "Signal To Noise: Vicente Yáñez, Kėkė Søl, Guro Kverndokk"
            signal.eventDate shouldBe LocalDate.of(2026, 7, 30)
            signal.eventType shouldBe EventType.CONCERT.name
            signal.imageUrl shouldBe "https://berlin.arkaoda.com/uploads/events/20260718141624.jpeg"
            signal.artists.map { it.name } shouldContainExactly
                listOf("Vicente Yáñez", "Kėkė Søl", "Guro Kverndokk")
        }

    @Test
    fun `keeps an unlabelled night artist-free through the merge`() =
        runTest {
            val mnjm = events(importer.importEvents(sourceUrl)).first { it.sourceId == "arkaoda:1321" }
            mnjm.eventType shouldBe EventType.OTHER.name
            mnjm.artists.shouldBeEmpty()
            mnjm.description.shouldNotBeNull() shouldContain "tickets at the door"
        }

    @Test
    fun `degrades to listing data when a detail page cannot be parsed`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(signalUrl) } returns
                Jsoup.parse("<html><body></body></html>", signalUrl)

            val signal = events(importer.importEvents(sourceUrl)).first { it.sourceId == "arkaoda:1320" }
            signal.title shouldBe "Signal To Noise: Vicente Yáñez, Kėkė Søl, Guro Kverndokk"
            signal.eventDate shouldBe LocalDate.of(2026, 7, 30)
            signal.artists shouldHaveSize 3
            // Only the detail page carries the description, so it stays absent.
            signal.description shouldBe null
        }

    @Test
    fun `degrades to listing data when a detail fetch fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(mnjmUrl) } throws RuntimeException("boom")

            val mnjm = events(importer.importEvents(sourceUrl)).first { it.sourceId == "arkaoda:1321" }
            mnjm.title shouldBe "MNJM"
            mnjm.description shouldBe null
        }

    @Test
    fun `imports nothing from a listing without event blocks`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(
                    document = Jsoup.parse("<html><body><div id='posts-list'></div></body></html>", sourceUrl),
                    etag = null,
                    lastModified = null
                )
            events(importer.importEvents(sourceUrl)).shouldBeEmpty()
        }

    @Test
    fun `returns NotModified when the listing page is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified
            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }
}
