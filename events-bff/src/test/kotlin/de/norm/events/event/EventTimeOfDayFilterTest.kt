package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.ClockConfiguration
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * `timeOfDay` repeats, and leaves out a row with neither a start nor a doors time (#2720). A row with an end matches
 * every slot it runs through; a row without one matches the slot of its start, else its doors time.
 */
class EventTimeOfDayFilterTest : BaseControllerTest() {
    private val day = LocalDate.now(ClockConfiguration.BERLIN).plusDays(1)

    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            val venue = insertVenue("Club", "club")
            insertEvent(venue, "Matinee", "matinee", day, startTime = LocalTime.of(6, 0))
            insertEvent(venue, "Afternoon", "afternoon", day.plusDays(1), startTime = LocalTime.of(17, 59))
            insertEvent(venue, "Concert", "concert", day.plusDays(2), startTime = LocalTime.of(18, 0))
            insertEvent(venue, "Doors Only", "doors-only", day.plusDays(3), doorsTime = LocalTime.of(21, 59))
            insertEvent(venue, "Rave", "rave", day.plusDays(4), eventType = "PARTY", startTime = LocalTime.of(22, 0))
            insertEvent(venue, "Afterhour", "afterhour", day.plusDays(5), eventType = "PARTY", startTime = LocalTime.of(5, 59))
            insertEvent(venue, "Timeless", "timeless", day.plusDays(6), eventType = "PARTY")
            insertEvent(
                venue,
                "Open Air",
                "open-air",
                day.plusDays(8),
                startTime = LocalTime.of(14, 0),
                endDate = day.plusDays(8),
                endTime = LocalTime.of(23, 0)
            )
            insertEvent(
                venue,
                "Festival",
                "festival",
                day.plusDays(9),
                eventType = "FESTIVAL",
                startTime = LocalTime.of(14, 0),
                endDate = day.plusDays(11),
                endTime = LocalTime.of(20, 0)
            )
            insertEvent(
                venue,
                "Club Night",
                "club-night",
                day.plusDays(10),
                eventType = "PARTY",
                startTime = LocalTime.of(23, 0),
                endDate = day.plusDays(11),
                endTime = LocalTime.of(6, 0)
            )
            insertEvent(venue, "Day Show", "day-show", day.plusDays(12), startTime = LocalTime.of(11, 0), endDate = day.plusDays(12))
        }

    @Test
    fun `GET events filters by time of night, any of several`() {
        mapOf(
            "" to
                listOf("matinee", "afternoon", "concert", "doors-only", "rave", "afterhour", "timeless") +
                listOf("open-air", "festival", "club-night", "day-show"),
            // The open-air runs 14:00 to 23:00, the festival three days, the club night from 23:00 to 06:00; the day
            // show ends the same day at an unknown hour, so its start decides.
            "timeOfDay=daytime" to listOf("matinee", "afternoon", "open-air", "festival", "day-show"),
            "timeOfDay=evening" to listOf("concert", "doors-only", "open-air", "festival"),
            "timeOfDay=late" to listOf("rave", "afterhour", "open-air", "festival", "club-night"),
            "timeOfDay=LATE&timeOfDay=daytime" to
                listOf("matinee", "afternoon", "rave", "afterhour", "open-air", "festival", "club-night", "day-show"),
            "timeOfDay=dawn" to emptyList(),
            "timeOfDay=dawn&timeOfDay=evening" to listOf("concert", "doors-only", "open-air", "festival")
        ).forEach { (query, slugs) ->
            webTestClient
                .get()
                .uri("/events?$query")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(slugs.size)
                .jsonPath("$.content[*].slug")
                .isEqualTo(slugs)
        }
    }

    @Test
    fun `GET events calendar filters by time of night`() {
        webTestClient
            .get()
            .uri("/events/calendar?from=$day&to=${day.plusDays(7)}&timeOfDay=late")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[*].slug")
            .isEqualTo(listOf("rave", "afterhour"))
    }

    @Test
    fun `GET events feed filters by time of night`() {
        val xml =
            webTestClient
                .get()
                .uri("/events/feed?timeOfDay=daytime")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!

        xml shouldContain "Matinee"
        xml shouldNotContain "Rave"
        xml shouldNotContain "Timeless"
    }
}
