package de.norm.events.scraper

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** Unit tests for [tecSourceId], the key every slug-keyed venue on The Events Calendar builds. */
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
}
