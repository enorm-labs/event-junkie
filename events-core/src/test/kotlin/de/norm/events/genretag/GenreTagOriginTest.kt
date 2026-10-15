package de.norm.events.genretag

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GenreTagOriginTest {
    // The names are stored, and V161's CHECK constraint lists them.
    @Test
    fun `the origins are the values the column accepts`() {
        GenreTagOrigin.entries.map { it.name } shouldBe listOf("SOURCE", "ARTIST", "DESCRIPTION")
    }
}
