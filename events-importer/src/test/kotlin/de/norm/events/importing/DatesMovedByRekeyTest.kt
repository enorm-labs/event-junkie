package de.norm.events.importing

import de.norm.events.event.EventEntity
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The guards of [datesMovedByRekey] that an import cannot reach, as the stale sweep never removes a row before tomorrow (#3067). */
class DatesMovedByRekeyTest {
    private val today = LocalDate.of(2026, 10, 20)

    private fun row(
        id: Long,
        date: LocalDate,
        title: String = "Tango Night"
    ): EventEntity =
        ScrapedEvent(title = title, eventType = "PARTY", eventDate = date, sourceId = "venue:$id-$date", sourceUrl = "https://venue.example/$id")
            .toEventEntity(venueId = 1L, venueSlug = "venue", eventSourceId = 1L)
            .copy(id = id)

    @Test
    fun `pairs a future removal with its insert`() {
        val new = row(2, today.plusDays(9))

        val pairs = datesMovedByRekey(listOf(row(1, today.plusDays(3))), listOf(new), listOf(new), today)

        pairs.single().first.id shouldBe 1
        pairs.single().first.eventDate shouldBe today.plusDays(3)
        pairs.single().second shouldBe new
    }

    @Test
    fun `a removed date that is not in the future pairs nothing`() {
        val new = row(2, today.plusDays(9))

        datesMovedByRekey(listOf(row(1, today)), listOf(new), listOf(new), today).shouldBeEmpty()
        datesMovedByRekey(listOf(row(1, today.minusDays(1))), listOf(new), listOf(new), today).shouldBeEmpty()
    }

    @Test
    fun `titles match without regard to case or whitespace`() {
        val new = row(2, today.plusDays(9), title = "tango   NIGHT ")

        datesMovedByRekey(listOf(row(1, today.plusDays(3))), listOf(new), listOf(new), today).size shouldBe 1
    }

    @Test
    fun `two removals with the title pair nothing`() {
        val new = row(2, today.plusDays(9))

        datesMovedByRekey(listOf(row(1, today.plusDays(3)), row(3, today.plusDays(4))), listOf(new), listOf(new), today).shouldBeEmpty()
    }
}
