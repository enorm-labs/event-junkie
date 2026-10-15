package de.norm.events.genretag

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DescriptionGenresTest {
    @Test
    fun `known genre words are read in order of mention`() {
        descriptionGenres("Eine Nacht voller Techno und Drum & Bass, dazu etwas Jazz.") shouldBe listOf("Techno", "Drum & Bass", "Jazz")
    }

    @Test
    fun `a two-word genre is one genre`() {
        descriptionGenres("Classic hip hop and punk rock all night") shouldBe listOf("Hip Hop", "Punk")
    }

    @Test
    fun `a genre in a support or guest clause is skipped`() {
        descriptionGenres("Indie pop from Leipzig. Support for a metal band.").shouldBe(listOf("Indie"))
        descriptionGenres("Jazz quartet, Support von einer Punk-Band") shouldBe listOf("Jazz")
        descriptionGenres("Folk duo plus a reggae DJ") shouldBe listOf("Folk")
        descriptionGenres("Soul night with special guest from the techno scene") shouldBe listOf("Soul")
        descriptionGenres("Blues trio + metal openers") shouldBe listOf("Blues")
    }

    @Test
    fun `a vocabulary word that prose uses in another sense is skipped`() {
        descriptionGenres("Bands from around the world in the Opera House, the party is in full swing").shouldBeEmpty()
    }

    @Test
    fun `a genre word inside a quoted title is skipped`() {
        descriptionGenres("Kingstar präsentiert: \"Whiskey Tango Foxtrot”, zurück zum Punk") shouldBe listOf("Punk")
        descriptionGenres("Ihr Album „Soul Kitchen“ ist Rock") shouldBe listOf("Rock")
        descriptionGenres("Their single ‘Disco Inferno’ and «Jazz Club» are rock songs") shouldBe listOf("Rock")
    }

    @Test
    fun `free text that names no known genre gives nothing`() {
        descriptionGenres("Die Band spielt Lieder aus ihrem neuen Album.").shouldBeEmpty()
        descriptionGenres(null).shouldBeEmpty()
        descriptionGenres("   ").shouldBeEmpty()
    }

    @Test
    fun `at most three genres are kept, and a long description is read whole`() {
        val long = "x ".repeat(5000) + "Rock, Pop, Jazz, Blues and Funk"
        descriptionGenres(long) shouldBe listOf("Rock", "Pop", "Jazz")
    }
}
