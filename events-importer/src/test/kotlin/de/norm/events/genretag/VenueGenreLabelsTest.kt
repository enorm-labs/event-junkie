package de.norm.events.genretag

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** [normalizeGenre] on the labels one venue writes into its genre field, named by venue. */
class VenueGenreLabelsTest {
    @Test
    fun `reads Drugstore's glued styles as the styles they are`() {
        // Events 10664 to 10667.
        normalizeGenre("Psych Doom, Blackened Doom, Fuzzed out PsychDoom") shouldBe listOf("Psych Doom", "Blackened Doom")
        normalizeGenre("BlackMetal, Antifascist BlackMetal, Oldschool DeathMetal") shouldBe listOf("Metal")
        normalizeGenre("Experimental Black Gaze, Post BlackMetal, Dungeon Synth / Noise") shouldBe listOf("Metal", "Dungeon Synth", "Noise")
    }

    @Test
    fun `Schokoladen's short forms land on the tags the filter knows`() {
        normalizeGenre("psych/alt-rock/indie-folk").shouldContainExactly("Psychedelic", "Rock", "Indie-folk")
        normalizeGenre("jangle-pop/indie-rock").shouldContainExactly("Indie")
    }

    @Test
    fun `Gretchen audience and theme labels are dropped`() {
        // Audience/theme/series labels Gretchen drops into the genre field, split on
        // "//" and "," — the real genres survive, the labels do not.
        normalizeGenre("FLINTA*// Pop, HipHop, House")
            .shouldContainExactly("Pop", "Hip Hop", "House")
        normalizeGenre("Männerparty // Fetish").shouldBeEmpty()
        normalizeGenre("Berbenautika, Cumbia, Breaks, HipHop")
            .shouldContainExactly("Latin", "Breaks", "Hip Hop")
    }

    @Test
    fun `Wild at Heart freeform style labels are normalized or dropped`() {
        // Idiosyncratic style labels from the retro concerts.php page.
        normalizeGenre("Ska/Reggea/Beat").shouldContainExactly("Ska", "Reggae", "Beat") // typo folded
        normalizeGenre("Kick Ass / Rock").shouldContainExactly("Rock") // non-genre dropped
        normalizeGenre("Kick-Ass-Rock").shouldContainExactly("Rock") // event 14938
        normalizeGenre("Hoochie Coochie Trashabilly").shouldContainExactly("Trashabilly") // event 14941
        normalizeGenre("Singer-Songwriter").shouldContainExactly("Singer-Songwriter")
        normalizeGenre("StonerPsychedelicMetal").shouldContainExactly("Metal")
        normalizeGenre("Rawk`n`Roll").shouldContainExactly("Rock")
        normalizeGenre("HC").shouldContainExactly("Hardcore")
    }
}
