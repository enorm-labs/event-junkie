package de.norm.events.venue

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DistrictTest {
    @Test
    fun `every borough round-trips through its wire form`() {
        District.entries.forEach { District.fromValue(it.value) shouldBe it }
    }

    @Test
    fun `all twelve Berlin boroughs are present`() {
        District.entries.size shouldBe 12
    }

    @Nested
    inner class Rejections {
        // The value that started #329. Two venues carried it, nine at the same postal code did not,
        // and a filter on the borough dropped them without failing.
        @Test
        fun `an Ortsteil is not a borough and is refused`() {
            shouldThrow<IllegalArgumentException> { District.fromValue("friedrichshain") }
        }

        @Test
        fun `the refusal names what was expected, so the caller can fix it`() {
            val message = shouldThrow<IllegalArgumentException> { District.fromValue("kreuzberg") }.message!!

            message.contains("kreuzberg") shouldBe true
            message.contains("friedrichshain-kreuzberg") shouldBe true
        }

        // There is deliberately no lenient fallback. A wrong borough moves a pin or removes a venue
        // from a radius search, and both are worse than refusing the write.
        @Test
        fun `an unknown value throws rather than falling back to a default`() {
            shouldThrow<IllegalArgumentException> { District.fromValue("wedding") }
        }
    }

    @Nested
    inner class Parsing {
        @Test
        fun `surrounding whitespace does not change the answer`() {
            District.fromValue("  mitte  ") shouldBe District.MITTE
        }

        @Test
        fun `case does not change the answer`() {
            District.fromValue("Friedrichshain-Kreuzberg") shouldBe District.FRIEDRICHSHAIN_KREUZBERG
        }
    }
}
