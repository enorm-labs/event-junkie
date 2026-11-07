package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Pins the exact strings: a changed `sourceId` re-keys every stored row of a venue (#4651). */
class SourceIdsTest {
    @Test
    fun `dateTitleId joins the prefix, the ISO date and the slugged title`() {
        EventSource.AEDEN.dateTitleId(LocalDate.of(2026, 10, 17), "Night One") shouldBe
            "${EventSource.AEDEN.sourceIdPrefix}2026-10-17-night-one"
    }

    @Test
    fun `lastPathSegment ignores a trailing slash`() {
        lastPathSegment("https://venue.example/events/night-1/") shouldBe "night-1"
        lastPathSegment("https://venue.example/events/night-1") shouldBe "night-1"
    }
}
