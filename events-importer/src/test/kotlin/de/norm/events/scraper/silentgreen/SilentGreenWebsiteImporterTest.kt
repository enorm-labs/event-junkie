package de.norm.events.scraper.silentgreen

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [SilentGreenWebsiteImporter].
 *
 * Mocks [HtmlFetcher] so the whole run — the month walk (entry page → September → the empty month
 * that ends it) and the de-duplicated detail fetches — executes offline against the saved fixtures.
 * Detail pages other than the three snapshotted ones are stubbed with an empty document, which is
 * also the "detail page adds nothing" path.
 */
class SilentGreenWebsiteImporterTest {
    private lateinit var importer: SilentGreenWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()

    private val entryUrl = "https://www.silent-green.net/programm"
    private val septemberUrl = "https://www.silent-green.net/programm/2026/9"
    private val octoberUrl = "https://www.silent-green.net/programm/2026/10"

    private fun detailUrl(slug: String) = "https://www.silent-green.net/programm/detail/$slug"

    private fun fixture(
        name: String,
        baseUrl: String
    ): Document =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/silentgreen/$name")!!
                .bufferedReader()
                .readText(),
            baseUrl
        )

    @BeforeEach
    fun setUp() {
        importer = SilentGreenWebsiteImporter(htmlFetcher)
        coEvery { htmlFetcher.fetchDocument(entryUrl) } returns fixture("silentgreen-month-august.html", entryUrl)
        coEvery { htmlFetcher.fetchDocument(septemberUrl) } returns fixture("silentgreen-month-september.html", septemberUrl)
        // The venue renders an empty calendar for any month asked for, which is what ends the walk.
        coEvery { htmlFetcher.fetchDocument(octoberUrl) } returns fixture("silentgreen-month-empty.html", octoberUrl)
        // Any detail page other than the snapshotted ones contributes nothing.
        coEvery { htmlFetcher.fetchDocument(match { it.startsWith(detailUrl("")) }) } returns
            Jsoup.parse("<html><body></body></html>", entryUrl)
        coEvery { htmlFetcher.fetchDocument(detailUrl("htrk")) } returns
            fixture("silentgreen-detail-konzert.html", detailUrl("htrk"))
        coEvery { htmlFetcher.fetchDocument(detailUrl("bjoern-melhus-lost-in-finity")) } returns
            fixture("silentgreen-detail-ausstellung.html", detailUrl("bjoern-melhus-lost-in-finity"))
        coEvery { htmlFetcher.fetchDocument(detailUrl("pop-kultur-festival-2026")) } returns
            fixture("silentgreen-detail-festival.html", detailUrl("pop-kultur-festival-2026"))
    }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.SILENT_GREEN
    }

    @Test
    fun `importEvents walks the month pages from the entry page until an empty month ends it`() =
        runTest {
            val result = importer.importEvents(entryUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            // 53 August rows + 17 September rows, minus the days that fold into two exhibition runs
            // (23 → 1 and 11 → 1, ADR-029); October renders an empty calendar and stops the walk.
            result.events shouldHaveSize 38
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(entryUrl) }
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(septemberUrl) }
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(octoberUrl) }
        }

    @Test
    fun `importEvents fetches each shared detail page once, not once per listed day`() =
        runTest {
            importer.importEvents(entryUrl)

            // 23 August days list the same exhibition; its page is requested a single time.
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(detailUrl("bjoern-melhus-lost-in-finity")) }
        }

    @Test
    fun `importEvents folds an exhibition's days into one run dated by its page`() =
        runTest {
            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            // 23 August days, one page whose date block says "Fr. 17.07.2026 – So. 23.08.2026": one run,
            // opening before the scraped month (ADR-029, #337).
            val exhibition = result.events.filter { it.sourceUrl == detailUrl("bjoern-melhus-lost-in-finity") }
            exhibition shouldHaveSize 1
            val run = exhibition.single()
            run.sourceId shouldBe "silent_green:bjoern-melhus-lost-in-finity"
            run.eventDate shouldBe LocalDate.of(2026, 7, 17)
            run.endDate shouldBe LocalDate.of(2026, 8, 23)
            run.endTime shouldBe null
            run.imageUrl?.contains("csm_NEU_melhus") shouldBe true
            run.description?.contains("Krisenmodus") shouldBe true

            // A page without a date block: the listed days bound the run.
            val islands = result.events.single { it.sourceUrl.contains("islands-of-time") }
            islands.eventDate shouldBe LocalDate.of(2026, 8, 6)
            islands.endDate shouldBe LocalDate.of(2026, 8, 16)

            // A festival's three days keep their own dates: each has its own lineup.
            result.events.count { it.sourceUrl.contains("pop-kultur-festival-2026") } shouldBe 3
        }

    @Test
    fun `importEvents merges the detail page's doors time into the calendar row`() =
        runTest {
            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            val concert = result.events.first { it.sourceId == "silent_green:2026-08-02-htrk" }
            concert.doorsTime shouldBe LocalTime.of(19, 0)
            concert.startTime shouldBe LocalTime.of(19, 45)
            concert.eventDate shouldBe LocalDate.of(2026, 8, 2)
            concert.description.shouldNotBeNull()
        }

    @Test
    fun `importEvents keeps the calendar data when a detail page fetch fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(detailUrl("htrk")) } throws RuntimeException("boom")

            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            val concert = result.events.first { it.sourceId == "silent_green:2026-08-02-htrk" }
            concert.title shouldBe "HTRK + Loraine James"
            concert.doorsTime.shouldBeNull()
        }

    @Test
    fun `importEvents returns no cache headers - the site sends none and later months are not covered`() =
        runTest {
            val result = importer.importEvents(entryUrl, etag = "\"abc\"", lastModified = "Mon, 04 Aug 2026 00:00:00 GMT")

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents returns no events when the entry month is empty`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(entryUrl) } returns fixture("silentgreen-month-empty.html", entryUrl)

            val result = importer.importEvents(entryUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.shouldHaveSize(0)
            coVerify(exactly = 0) { htmlFetcher.fetchDocument(septemberUrl) }
        }
}
