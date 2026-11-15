package de.norm.events.scraper

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The subtitle [ScrapedEvent.toEventEntity] stores. Wühlmäuse and PUNCH L!NE repeat the title as the subtitle. */
class ScrapedEventSubtitleTest {
    private fun storedSubtitle(
        title: String,
        subtitle: String?
    ) = ScrapedEvent(
        title = title,
        subtitle = subtitle,
        eventDate = LocalDate.of(2027, 12, 22),
        sourceId = "wuehlmaeuse:1",
        sourceUrl = "https://www.wuehlmaeuse.de/programm/1"
    ).toEventEntity(venueId = 1L, venueSlug = "wuehlmaeuse", eventSourceId = 1L).subtitle

    @Test
    fun `a subtitle that repeats the title is not stored, whatever its case and spacing`() {
        storedSubtitle("Pater  Brown", " pater brown ").shouldBeNull()
    }

    @Test
    fun `a subtitle that repeats the title less its sold-out marker is not stored`() {
        storedSubtitle("Neschastny Sluchai - ausverkauft", "Neschastny Sluchai").shouldBeNull()
    }

    @Test
    fun `a subtitle that only starts with the title is stored as read`() {
        storedSubtitle("Pater Brown", "Pater Brown – Das Musical") shouldBe "Pater Brown – Das Musical"
        storedSubtitle("Pater Brown", null).shouldBeNull()
    }
}
