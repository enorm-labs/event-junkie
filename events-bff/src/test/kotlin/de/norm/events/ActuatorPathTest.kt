package de.norm.events

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.jupiter.api.Test

class ActuatorPathTest {
    @Test
    fun `the base path and everything under it match`() {
        isActuatorPath("/actuator", "/actuator").shouldBeTrue()
        isActuatorPath("/actuator/health/liveness", "/actuator").shouldBeTrue()
    }

    @Test
    fun `a route that only starts the same does not match`() {
        isActuatorPath("/actuatorial", "/actuator").shouldBeFalse()
        isActuatorPath("/api/events", "/actuator").shouldBeFalse()
    }

    @Test
    fun `a trailing slash on the base path changes nothing`() {
        isActuatorPath("/manage", "/manage/").shouldBeTrue()
        isActuatorPath("/manage/prometheus", "/manage/").shouldBeTrue()
    }
}
