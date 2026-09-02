package de.norm.events.event

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class EventTypeTest {
    @Test
    fun `parseOrDefault returns CONCERT for exact match`() {
        EventType.parseOrDefault("CONCERT") shouldBe EventType.CONCERT
    }

    @Test
    fun `parseOrDefault is case-insensitive`() {
        EventType.parseOrDefault("concert") shouldBe EventType.CONCERT
        EventType.parseOrDefault("Festival") shouldBe EventType.FESTIVAL
        EventType.parseOrDefault("pArTy") shouldBe EventType.PARTY
    }

    @Test
    fun `parseOrDefault trims whitespace`() {
        EventType.parseOrDefault("  QUIZ  ") shouldBe EventType.QUIZ
        EventType.parseOrDefault("\tCLUB_NIGHT\n") shouldBe EventType.CLUB_NIGHT
    }

    @Test
    fun `parseOrDefault returns all valid enum values`() {
        EventType.parseOrDefault("CONCERT") shouldBe EventType.CONCERT
        EventType.parseOrDefault("FESTIVAL") shouldBe EventType.FESTIVAL
        EventType.parseOrDefault("PARTY") shouldBe EventType.PARTY
        EventType.parseOrDefault("QUIZ") shouldBe EventType.QUIZ
        EventType.parseOrDefault("CLUB_NIGHT") shouldBe EventType.CLUB_NIGHT
        EventType.parseOrDefault("SHOW") shouldBe EventType.SHOW
        EventType.parseOrDefault("SCREENING") shouldBe EventType.SCREENING
        EventType.parseOrDefault("EXHIBITION") shouldBe EventType.EXHIBITION
        EventType.parseOrDefault("READING") shouldBe EventType.READING
        EventType.parseOrDefault("OTHER") shouldBe EventType.OTHER
    }

    @Test
    fun `parseOrDefault returns OTHER for unknown values`() {
        EventType.parseOrDefault("UNKNOWN") shouldBe EventType.OTHER
        EventType.parseOrDefault("rave") shouldBe EventType.OTHER
        EventType.parseOrDefault("") shouldBe EventType.OTHER
    }
}
