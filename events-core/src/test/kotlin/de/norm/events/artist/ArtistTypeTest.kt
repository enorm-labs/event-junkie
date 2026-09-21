package de.norm.events.artist

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** The type vocabulary, which the importer stores by name and the CHECK constraints of V040 repeat. */
class ArtistTypeTest {
    @Test
    fun `the five types round-trip by name`() {
        ArtistType.entries.map { it.name }.shouldContainExactly("PERSON", "GROUP", "ORCHESTRA", "CHOIR", "OTHER")
        ArtistType.entries.forEach { ArtistType.valueOf(it.name) shouldBe it }
    }

    @Test
    fun `MusicBrainz's type string maps by name, anything else is OTHER, and no type is null`() {
        ArtistType.fromMusicBrainz("Person") shouldBe ArtistType.PERSON
        ArtistType.fromMusicBrainz("Group") shouldBe ArtistType.GROUP
        ArtistType.fromMusicBrainz("Orchestra") shouldBe ArtistType.ORCHESTRA
        ArtistType.fromMusicBrainz("Choir") shouldBe ArtistType.CHOIR
        ArtistType.fromMusicBrainz("Character") shouldBe ArtistType.OTHER
        ArtistType.fromMusicBrainz("Other") shouldBe ArtistType.OTHER
        ArtistType.fromMusicBrainz(null).shouldBeNull()
    }

    @Test
    fun `only the ensembles may carry a founding date and place`() {
        ArtistType.entries.filter { it.isEnsemble }.shouldContainExactly(ArtistType.GROUP, ArtistType.ORCHESTRA, ArtistType.CHOIR)
    }

    @Test
    fun `a new artist has no type and no founding data`() {
        val artist = Artist(name = "Accept", slug = "accept")
        artist.artistType.shouldBeNull()
        artist.founded.shouldBeNull()
        artist.foundedIn.shouldBeNull()
    }
}
