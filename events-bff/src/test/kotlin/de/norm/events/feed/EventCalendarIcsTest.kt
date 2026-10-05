package de.norm.events.feed

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EventCalendarIcsTest {
    @Test
    fun `escapes the characters RFC 5545 TEXT reserves, and every kind of line break`() {
        EventCalendarIcs.escapeText("a\\b;c,d\ne\r\nf\rg") shouldBe "a\\\\b\\;c\\,d\\ne\\nf\\ng"
    }

    @Test
    fun `leaves a line of 75 octets whole and folds the next octet onto a continuation line`() {
        EventCalendarIcs.foldLine("x".repeat(75)) shouldBe "x".repeat(75)
        EventCalendarIcs.foldLine("x".repeat(76)) shouldBe "x".repeat(75) + "\r\n x"
    }

    @Test
    fun `counts UTF-8 octets and never splits a character, a surrogate pair included`() {
        val folded = EventCalendarIcs.foldLine("SUMMARY:" + "ü".repeat(40) + "🎷".repeat(20))

        val lines = folded.split("\r\n")
        lines.forEachIndexed { index, line ->
            val octets = line.toByteArray(Charsets.UTF_8).size
            check(octets <= 75) { "line $index has $octets octets" }
            check(!line.contains('�')) { "line $index split a character" }
        }
        lines.drop(1).forEach { it[0] shouldBe ' ' }
        folded.replace("\r\n ", "") shouldBe "SUMMARY:" + "ü".repeat(40) + "🎷".repeat(20)
    }

    @Test
    fun `renders an empty calendar as a valid one`() {
        val lines = EventCalendarIcs.render("en", emptyList()).split("\r\n").filter { it.isNotEmpty() }

        lines.first() shouldBe "BEGIN:VCALENDAR"
        lines.last() shouldBe "END:VCALENDAR"
        lines.filter { it == "BEGIN:VEVENT" } shouldHaveSize 0
    }
}
