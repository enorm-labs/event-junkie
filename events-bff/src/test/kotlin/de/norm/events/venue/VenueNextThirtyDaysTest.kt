package de.norm.events.venue

import de.norm.events.BaseControllerTest
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** The edges of the 30-day count (#2694), at a fixed moment: an effective start from now, up to 30 days on. */
class VenueNextThirtyDaysTest : BaseControllerTest() {
    private val now = LocalDateTime.of(2030, 6, 10, 21, 0)

    @Test
    fun `the count takes the effective start from now to 30 days on`(): Unit =
        runBlocking {
            val venue = insertVenue("Lido", "lido")
            val today = now.toLocalDate()
            val lastDay = LocalDate.of(2030, 7, 10)
            // Started at the concert slot, 20:00, an hour ago: still upcoming, but not in the window.
            insertEvent(venue, "Started", "started", today)
            // A timeless party takes the 23:00 slot, so it is still ahead tonight.
            insertEvent(venue, "Party", "party", today, eventType = "PARTY")
            insertEvent(venue, "Last in", "last-in", lastDay, startTime = LocalTime.of(20, 59))
            insertEvent(venue, "First out", "first-out", lastDay, startTime = LocalTime.of(21, 0))

            val row = VenueSearchRepository(databaseClient).search(VenueFilter(), now, PageRequest.of(0, 1)).rows.single()

            row.upcomingEventCount shouldBe 4
            row.upcomingNext30DaysCount shouldBe 2
        }
}
