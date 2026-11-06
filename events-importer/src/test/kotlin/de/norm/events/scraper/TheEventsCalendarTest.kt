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
import java.time.Clock
import java.time.Instant
import java.time.Period

/** Unit tests for [tecSourceId], the key every slug-keyed venue on The Events Calendar builds, and the first request of [AbstractTecImporter]. */
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
}
