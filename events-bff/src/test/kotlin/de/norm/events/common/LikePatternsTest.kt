package de.norm.events.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LikePatternsTest {
    @Test
    fun `the three metacharacters are escaped and nothing else is touched`() {
        "100% live_set".escapeLike() shouldBe "100\\% live\\_set"
        "Møbius Trio".escapeLike() shouldBe "Møbius Trio"
    }

    @Test
    fun `a backslash is escaped first, so an escaped percent is not escaped twice`() {
        "\\%".escapeLike() shouldBe "\\\\\\%"
    }
}
