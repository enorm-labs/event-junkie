package de.norm.events.artist

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** The verdict vocabulary, which the importer stores by name and the CHECK constraint of V037 repeats. */
class MusicBrainzMatchTest {
    @Test
    fun `the four states round-trip by name`() {
        MusicBrainzMatch.entries.map { it.name }.shouldContainExactly("EXACT", "AMBIGUOUS", "NONE", "UNCHECKED")
        MusicBrainzMatch.entries.forEach { MusicBrainzMatch.valueOf(it.name) shouldBe it }
    }

    @Test
    fun `a new artist starts UNCHECKED`() {
        Artist(name = "Accept", slug = "accept").musicbrainzMatch shouldBe MusicBrainzMatch.UNCHECKED
    }
}
