package de.norm.events.scraper

import de.norm.events.common.BERLIN
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Period

/** Unit tests for [tecSourceId], the key every slug-keyed venue on The Events Calendar builds, and the requests and walk of [AbstractTecImporter]. */
class TheEventsCalendarTest {
    private val mapper = JsonMapper.builder().build()

    private fun sourceId(
        url: String?,
        slug: String = "opera-days"
    ): String {
        val urlField = url?.let { """"url": "$it", """ }.orEmpty()
        return mapper.readTree("""{ $urlField"slug": "$slug" }""").tecSourceId(EventSource.SOULCAT, slug)
    }

    @Test
    fun `keeps the bare slug for an event whose URL ends on it`() {
        sourceId("https://venue.example/event/opera-days/") shouldBe "soulcat:opera-days"
    }

    @Test
    fun `adds the date to each date of a recurring event, so dates sharing a slug stay apart`() {
        val ids =
            listOf("2026-08-25", "2026-08-26").map { sourceId("https://venue.example/event/opera-days/$it/") }

        ids shouldContainExactly listOf("soulcat:opera-days-2026-08-25", "soulcat:opera-days-2026-08-26")
    }

    @Test
    fun `adds the number of a second show on the same date`() {
        listOf(1, 2).map { sourceId("https://venue.example/event/opera-days/2026-05-30/$it/") } shouldContainExactly
            listOf("soulcat:opera-days-2026-05-30-1", "soulcat:opera-days-2026-05-30-2")
    }

    @Test
    fun `keeps the date of a series down to its last date, so its key does not change`() {
        sourceId("https://venue.example/event/opera-days/2026-08-30/") shouldBe "soulcat:opera-days-2026-08-30"
    }

    @Test
    fun `ignores a segment after the slug that is not a date`() {
        sourceId("https://venue.example/event/opera-days/ical/") shouldBe "soulcat:opera-days"
    }

    @Test
    fun `matches a percent-encoded slug against the raw path`() {
        sourceId("https://venue.example/event/caf%C3%A9-night/2026-08-25/", slug = "caf%c3%a9-night") shouldBe
            "soulcat:caf%c3%a9-night-2026-08-25"
    }

    @Test
    fun `keeps the bare slug when the URL is missing, unparseable or does not name the slug`() {
        sourceId(null) shouldBe "soulcat:opera-days"
        sourceId("not a url/2026-08-25/") shouldBe "soulcat:opera-days"
        sourceId("https://venue.example/?p=123/2026-08-25") shouldBe "soulcat:opera-days"
    }

    private fun importer(
        client: ApiClient,
        lookback: Period?
    ) = object : AbstractTecImporter(
        client,
        "Venue",
        { TecPage(emptyList(), null) },
        1,
        lookback,
        Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), BERLIN)
    ) {
        override val eventSource = EventSource.SOULCAT
    }

    /** An importer over a two-page programme: the first page links to [SECOND_PAGE], and the second is the last. */
    private fun twoPageImporter(
        client: ApiClient,
        maxPages: Int = 2
    ) = object : AbstractTecImporter(
        client,
        "Venue",
        { body -> if (body == FIRST_BODY) TecPage(listOf(event("first")), SECOND_PAGE) else TecPage(listOf(event("second")), null) },
        maxPages
    ) {
        override val eventSource = EventSource.SOULCAT
    }

    private fun event(slug: String) =
        ScrapedEvent(title = slug, eventDate = LocalDate.of(2026, 12, 1), sourceId = "soulcat:$slug", sourceUrl = "https://venue.example/event/$slug/")

    private fun twoPageClient(secondPageFails: Boolean = false): ApiClient =
        mockk {
            coEvery { fetchJson("https://venue.example/events?per_page=50") } returns FIRST_BODY
            if (secondPageFails) {
                coEvery { fetchJson(SECOND_PAGE) } throws IOException("timeout")
            } else {
                coEvery { fetchJson(SECOND_PAGE) } returns "{}"
            }
        }

    private fun requestedUrl(
        sourceUrl: String,
        lookback: Period?
    ): String {
        val client: ApiClient = mockk()
        val requested = slot<String>()
        coEvery { client.fetchJson(capture(requested)) } returns "{}"
        runTest { importer(client, lookback).importEvents(sourceUrl) }
        return requested.captured
    }

    @Test
    fun `asks for the plain programme with no look-back`() {
        requestedUrl("https://venue.example/events", null) shouldBe "https://venue.example/events?per_page=50"
    }

    @Test
    fun `starts a look-back that far before today`() {
        requestedUrl("https://venue.example/events", TEC_RUNNING_LOOKBACK) shouldBe
            "https://venue.example/events?start_date=2026-07-09&per_page=50"
    }

    @Test
    fun `adds a look-back to a source URL that already holds a query`() {
        requestedUrl("https://venue.example/events?categories=buehne", TEC_RUNNING_LOOKBACK) shouldBe
            "https://venue.example/events?categories=buehne&start_date=2026-07-09&per_page=50"
    }

    @Test
    fun `reports a walk to the last page as the whole programme`() =
        runTest {
            val importer = twoPageImporter(twoPageClient())

            val result = importer.importEvents("https://venue.example/events") as ImportResult.Success

            importer.listsWholeProgramme shouldBe true
            result.complete shouldBe true
            result.events.map { it.sourceId } shouldContainExactly listOf("soulcat:first", "soulcat:second")
        }

    @Test
    fun `reports a walk that a failed page ends as incomplete, so the cleanup is skipped`() =
        runTest {
            val result = twoPageImporter(twoPageClient(secondPageFails = true)).importEvents("https://venue.example/events") as ImportResult.Success

            result.complete shouldBe false
            result.events.map { it.sourceId } shouldContainExactly listOf("soulcat:first")
        }

    @Test
    fun `reports a walk that the page cap ends as incomplete, so the cleanup is skipped`() =
        runTest {
            val result = twoPageImporter(twoPageClient(), maxPages = 1).importEvents("https://venue.example/events") as ImportResult.Success

            result.complete shouldBe false
        }

    private companion object {
        const val FIRST_BODY = "first"
        const val SECOND_PAGE = "https://venue.example/events?per_page=50&page=2"
    }
}
