package de.norm.events.scraper

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.LocalDate

/** Pins [AbstractTwoPageWebsiteImporter]'s orchestration on synthetic pages, before #4643 moves it. */
class AbstractTwoPageWebsiteImporterTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val entryUrl = "https://venue.example/events"
    private val overviewDate = LocalDate.of(2026, 10, 1)
    private val detailDate = LocalDate.of(2026, 10, 2)

    /** An overview page: one `<li>` per event, its id the slug, `data-dateless` for a row with no date, and an optional next link. */
    private fun overview(
        url: String,
        vararg ids: String,
        next: String? = null
    ): Document =
        Jsoup.parse(
            "<ul>${ids.joinToString("") { "<li id='$it'></li>" }}</ul>" + next?.let { "<a class='next' href='$it'></a>" }.orEmpty(),
            url
        )

    private val scrapeOverview: (Document, String) -> List<ScrapedEvent> = { document, _ ->
        document.select("li").map {
            val id = it.id()
            ScrapedEvent(
                title = "overview $id",
                eventDate = if (id.startsWith("dateless")) UNRESOLVED_EVENT_DATE else overviewDate,
                sourceUrl = "https://venue.example/e/$id",
                sourceId = id,
                ticketUrl = "https://tickets.example/$id"
            )
        }
    }

    /** A detail page carries a title, an optional description in `<p>`, and an optional `hreflang="en"` link. */
    private fun detail(
        url: String,
        description: String? = "Deutsch",
        alternate: String? = null
    ): Document =
        Jsoup.parse(
            "<head>${alternate?.let { "<link rel='alternate' hreflang='en' href='$it'>" }.orEmpty()}</head>" +
                "<body><h1>detail</h1>${description?.let { "<p>$it</p>" }.orEmpty()}</body>",
            url
        )

    private fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? =
        document.selectFirst("h1")?.let {
            ScrapedEvent(
                title = it.text(),
                eventDate = detailDate,
                sourceUrl = url,
                sourceId = url.substringAfterLast('/'),
                description = document.selectFirst("p")?.text()
            )
        }

    private open inner class TwoPage : AbstractTwoPageWebsiteImporter(htmlFetcher, scrapeOverview, ::scrapeDetail) {
        override val eventSource: EventSource = EventSource.ROSA
    }

    private inner class Paged : TwoPage() {
        override fun nextOverviewPage(
            document: Document,
            url: String
        ): String? = document.nextPageUrl("a.next")
    }

    private inner class Bilingual : TwoPage() {
        override val secondLanguage = SecondLanguagePage("en") { it.selectFirst("p")?.text() }
    }

    private inner class Configured : TwoPage() {
        override val cookies: Map<String, String> = mapOf("gate" to "1")
        override val maxOverviewPages: Int = 2
        override val sharedPages =
            SharedPages(parse = { it.selectFirst("h1")?.text() }, apply = { heading: String, event -> event.copy(subtitle = heading) })

        override fun nextOverviewPage(
            document: Document,
            url: String
        ): String? = document.nextPageUrl("a.next")
    }

    private fun stubEntry(document: Document) {
        coEvery { htmlFetcher.fetch(entryUrl, any(), any(), any()) } returns FetchResult.Success(document, "\"v1\"", "Sat, 07 Nov 2026 10:00:00 GMT")
    }

    private fun stubDetail(
        id: String,
        document: Document? = detail("https://venue.example/e/$id")
    ) {
        val url = "https://venue.example/e/$id"
        if (document == null) {
            coEvery { htmlFetcher.fetchDocument(url) } throws HttpFetchException(503, url)
        } else {
            coEvery { htmlFetcher.fetchDocument(url) } returns document
        }
    }

    @Test
    fun `answers a 304 on the overview with NotModified`() =
        runTest {
            coEvery { htmlFetcher.fetch(entryUrl, "\"v1\"", null, any()) } returns FetchResult.NotModified

            TwoPage().importEvents(entryUrl, etag = "\"v1\"") shouldBe ImportResult.NotModified
            TwoPage().fetchesBeyondEntryPage shouldBe true
        }

    @Test
    fun `takes the detail page over the overview, fills its gaps from the overview, and keeps the overview's validators`() =
        runTest {
            stubEntry(overview(entryUrl, "a"))
            stubDetail("a")

            val result = TwoPage().importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            val event = result.events.single()
            event.title shouldBe "detail"
            event.eventDate shouldBe detailDate
            event.description shouldBe "Deutsch"
            event.ticketUrl shouldBe "https://tickets.example/a"
            event.detailUnavailable shouldBe false
            result.etag shouldBe "\"v1\""
            result.lastModified shouldBe "Sat, 07 Nov 2026 10:00:00 GMT"
            result.complete shouldBe true
        }

    @Test
    fun `degrades to the overview row, flagged with the image owned, when the detail page fails or parses to nothing`() =
        runTest {
            stubEntry(overview(entryUrl, "fails", "empty"))
            stubDetail("fails", document = null)
            stubDetail("empty", Jsoup.parse("<p>no heading</p>", "https://venue.example/e/empty"))

            val events = TwoPage().importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>().events

            events.map { it.title } shouldContainExactly listOf("overview fails", "overview empty")
            events.all { it.detailUnavailable && it.detailPageOwns == setOf(ScrapedField.IMAGE) } shouldBe true
            // A row with a date gets one attempt.
            coVerify(exactly = 1) { htmlFetcher.fetchDocument("https://venue.example/e/fails") }
        }

    @Test
    fun `retries a dateless row's detail page once, and drops the row and counts it when both attempts fail`() =
        runTest {
            stubEntry(overview(entryUrl, "a", "dateless"))
            stubDetail("a")
            stubDetail("dateless", document = null)

            val result = TwoPage().importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.map { it.sourceId } shouldContainExactly listOf("a")
            result.droppedUnresolvedDate shouldBe 1
            coVerify(exactly = 2) { htmlFetcher.fetchDocument("https://venue.example/e/dateless") }
        }

    @Test
    fun `spares a dateless row whose detail page failed from the stale cleanup, and logs its sourceId with the exception`() =
        runTest {
            stubEntry(overview(entryUrl, "a", "dateless"))
            stubDetail("a")
            stubDetail("dateless", document = null)
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            root.addAppender(appender)

            val result =
                try {
                    TwoPage().importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()
                } finally {
                    root.detachAppender(appender)
                }

            result.spared shouldContainExactly setOf("dateless")
            // Only that row: the run stays complete, so the cleanup still removes what the venue took down.
            result.complete shouldBe true
            val spared = appender.list.single { it.level == Level.WARN && it.formattedMessage.contains("sparing its stored event from the stale cleanup") }
            spared.throwableProxy.className shouldBe HttpFetchException::class.java.name
            spared.keyValuePairs.associate { it.key to it.value }[LogFields.EVENT_SOURCE_ID] shouldBe "dateless"
        }

    @Test
    fun `keeps a dateless row whose detail page supplies the date`() =
        runTest {
            stubEntry(overview(entryUrl, "dateless"))
            stubDetail("dateless")

            val result = TwoPage().importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.single().eventDate shouldBe detailDate
            result.droppedUnresolvedDate shouldBe 0
            result.spared.shouldBeEmpty()
        }

    @Test
    fun `walks the overview's next links, keeping an event once across a page boundary`() =
        runTest {
            stubEntry(overview(entryUrl, "a", "b", next = "?page=2"))
            coEvery { htmlFetcher.fetchDocument("$entryUrl?page=2") } returns overview("$entryUrl?page=2", "b", "c")
            listOf("a", "b", "c").forEach { stubDetail(it) }

            val result = Paged().importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.map { it.sourceId } shouldContainExactly listOf("a", "b", "c")
            result.complete shouldBe true
        }

    @Test
    fun `stops at twelve overview pages and reports the run incomplete`() =
        runTest {
            stubEntry(overview(entryUrl, next = "?page=2"))
            coEvery { htmlFetcher.fetchDocument(match { it.startsWith("$entryUrl?page=") }) } answers {
                val n = firstArg<String>().pageNumber()
                overview(firstArg(), next = "?page=${n + 1}")
            }

            val result = Paged().importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.shouldBeEmpty()
            result.complete shouldBe false
            coVerify(exactly = 11) { htmlFetcher.fetchDocument(match { it.startsWith("$entryUrl?page=") }) }
        }

    @Test
    fun `reads the second language from the detail page's alternate, only beside a detail description`() =
        runTest {
            stubEntry(overview(entryUrl, "a", "bare"))
            stubDetail("a", detail("https://venue.example/e/a", alternate = "https://venue.example/en/e/a"))
            stubDetail("bare", detail("https://venue.example/e/bare", description = null, alternate = "https://venue.example/en/e/bare"))
            coEvery { htmlFetcher.fetchDocument("https://venue.example/en/e/a") } returns Jsoup.parse("<p>English</p>")

            val events = Bilingual().importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>().events

            events.single { it.sourceId == "a" }.descriptionAlt shouldBe "English"
            events.single { it.sourceId == "bare" }.descriptionAlt.shouldBeNull()
            coVerify(exactly = 0) { htmlFetcher.fetchDocument("https://venue.example/en/e/bare") }
        }

    @Test
    fun `sends its cookies, honours its own page cap, and reads the pages its rows share`() =
        runTest {
            stubEntry(overview(entryUrl, "a", next = "?page=2"))
            coEvery { htmlFetcher.fetchDocument("$entryUrl?page=2") } returns overview("$entryUrl?page=2", "b", next = "?page=3")
            listOf("a", "b").forEach { stubDetail(it) }

            val result = Configured().importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.map { it.sourceId } shouldContainExactly listOf("a", "b")
            result.events.all { it.subtitle == "detail" } shouldBe true
            result.complete shouldBe false
            coVerify { htmlFetcher.fetch(entryUrl, null, null, mapOf("gate" to "1")) }
            coVerify(exactly = 0) { htmlFetcher.fetchDocument("$entryUrl?page=3") }
        }
}
