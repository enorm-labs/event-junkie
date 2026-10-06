package de.norm.events.feed

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CalendarNameTest {
    @Test
    fun `is the site's name alone without a name, or with a blank one`() {
        CalendarName.of(null) shouldBe "Event Junkie"
        CalendarName.of(" \t ") shouldBe "Event Junkie"
    }

    @Test
    fun `keeps a name of exactly the limit, and cuts one long label without splitting a surrogate pair`() {
        CalendarName.of("x".repeat(120)) shouldBe "Event Junkie · " + "x".repeat(120)
        CalendarName.of("x".repeat(119) + "🎷🎷") shouldBe "Event Junkie · " + "x".repeat(119)
    }

    @Test
    fun `drops control characters, so a name stays on one content line`() {
        CalendarName.of("Jazz\r\nSUMMARY:x\u0000") shouldBe "Event Junkie · JazzSUMMARY:x"
    }
}
