package de.norm.events.scraper.barjedervernunft

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Unit tests for [BarJederVernunftWebsiteImporter].
 *
 * Uses saved snapshots of the calendar page and both show pages it links to, with
 * [HtmlFetcher] mocked so no real HTTP requests are made. The fixture calendar is a
 * 27-date residency plus one guest night, which is exactly the shape the importer's
 * per-show fetch de-duplication exists for.
 */
class BarJederVernunftWebsiteImporterTest {
    private lateinit var importer: BarJederVernunftWebsiteImporter
    private val htmlFetcher: HtmlFetcher = mockk()

    private val sourceUrl = "https://www.bar-jeder-vernunft.de/de/programm/kalender.html"

    @BeforeEach
    fun setUp() {
        importer = BarJederVernunftWebsiteImporter(htmlFetcher)

        coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
            FetchResult.Success(
                document = Jsoup.parse(loadFixture("barjedervernunft-overview.html"), sourceUrl),
                etag = "\"cal-1\"",
                lastModified = "Fri, 31 Jul 2026 10:00:00 GMT"
            )
        coEvery { htmlFetcher.fetchDocument(RESIDENCY_URL) } returns
            Jsoup.parse(loadFixture("barjedervernunft-show-oh-what-a-night.html"), RESIDENCY_URL)
        coEvery { htmlFetcher.fetchDocument(GUEST_URL) } returns
            Jsoup.parse(loadFixture("barjedervernunft-show-happy-disharmonists.html"), GUEST_URL)
    }

    private fun loadFixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/barjedervernunft/$name")!!
            .bufferedReader()
            .readText()

    @Test
    fun `importEvents extracts every performance date from the calendar`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 28
        }

    @Test
    fun `importEvents fetches each distinct show page exactly once, not once per date`() =
        runTest {
            importer.importEvents(sourceUrl)

            // 28 dates resolve to 2 show pages — fetching per date would hammer the venue
            // with 26 redundant requests, serialised by the per-host politeness throttle.
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(RESIDENCY_URL) }
            coVerify(exactly = 1) { htmlFetcher.fetchDocument(GUEST_URL) }
        }

    @Test
    fun `importEvents applies the show page fields to every date of that show`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()

            val residency = result.events.filter { it.sourceUrl == RESIDENCY_URL }
            residency shouldHaveSize 27
            residency.map { it.genre }.distinct() shouldContainExactly listOf("Musik-Show")
            residency.map { it.pricePresale }.distinct() shouldContainExactly listOf(BigDecimal("19.90"))
            residency.forEach { it.description.shouldNotBeNull() shouldContain "Feelgood-Music at its best" }
        }

    @Test
    fun `importEvents types a staged production as SHOW and mints no artist from its name`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()

            val show = result.events.first { it.title == "Oh What A Night!" }
            show.eventType shouldBe "SHOW"
            show.genre shouldBe "Musik-Show"
            show.priceNote shouldBe "Ab 19,90 € bis 49,90 €"
            show.free shouldBe false
            show.artists.shouldBeEmpty()
        }

    @Test
    fun `importEvents types a music genre as CONCERT and bills its performer`() =
        runTest {
            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()

            val concert = result.events.single { it.sourceUrl == GUEST_URL }
            concert.title shouldBe "The Happy Disharmonists"
            concert.eventType shouldBe "CONCERT"
            concert.genre shouldBe "A cappella"
            concert.pricePresale shouldBe BigDecimal("12.90")
            concert.artists shouldContainExactly listOf(ScrapedArtist(name = "The Happy Disharmonists", role = "HEADLINER", titleDerived = true))
        }

    @Test
    fun `importEvents propagates conditional response headers`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag shouldBe "\"cal-1\""
            result.lastModified shouldBe "Fri, 31 Jul 2026 10:00:00 GMT"
        }

    @Test
    fun `importEvents returns NotModified when the calendar is unchanged`() =
        runTest {
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns FetchResult.NotModified

            importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.NotModified>()
        }

    @Test
    fun `importEvents returns an empty list for a calendar without cards`() =
        runTest {
            val emptyDoc = Jsoup.parse("<html><body><div class='events-list'></div></body></html>", sourceUrl)
            coEvery { htmlFetcher.fetch(sourceUrl, any(), any()) } returns
                FetchResult.Success(document = emptyDoc, etag = null, lastModified = null)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `importEvents keeps the calendar data when a show page fetch fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(any()) } throws RuntimeException("Network error")

            val result = importer.importEvents(sourceUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 28

            val show = result.events.first()
            show.title shouldBe "Oh What A Night!"
            show.genre shouldBe null
            show.pricePresale shouldBe null
            show.eventType shouldBe null
            // The JSON-LD teaser survives as the description when the full text is unavailable.
            show.description.shouldNotBeNull() shouldContain "Oh What A Night: Ein musikalischer Showhit"
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.BAR_JEDER_VERNUNFT
    }

    private companion object {
        const val RESIDENCY_URL =
            "https://www.bar-jeder-vernunft.de/de/programm/programmuebersicht/oh-what-a-night-frankie-valli-show.html"
        const val GUEST_URL =
            "https://www.bar-jeder-vernunft.de/de/programm/programmuebersicht/the-happy-disharmonists-40-jahre.html"
    }
}
