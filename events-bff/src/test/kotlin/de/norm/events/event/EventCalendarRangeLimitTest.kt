package de.norm.events.event

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The frontend's `clampToCalendarWindow` mirrors this limit, so a change here changes it too. */
class EventCalendarRangeLimitTest : BaseControllerTest() {
    @Test
    fun `GET events calendar accepts 92 days counting both ends and rejects a 93rd`() {
        val from = LocalDate.now(BERLIN)

        webTestClient
            .get()
            .uri("/events/calendar?from=$from&to=${from.plusDays(91)}")
            .exchange()
            .expectStatus()
            .isOk

        webTestClient
            .get()
            .uri("/events/calendar?from=$from&to=${from.plusDays(92)}")
            .exchange()
            .expectStatus()
            .isBadRequest
    }
}
