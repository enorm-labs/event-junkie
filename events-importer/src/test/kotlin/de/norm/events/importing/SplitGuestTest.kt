package de.norm.events.importing

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A bracketed guest's role at sync: a headliner's featured guest is GUEST, an added act SUPPORT (#5359). */
class SplitGuestTest {
    private fun roles(
        name: String,
        role: ArtistRole
    ) = splitGuest(ScrapedArtist(name = name, role = role)).map { it.name to it.role }

    @Test
    fun `a headliner's featured guest is a guest`() {
        roles("Dosenstolz (feat. Tancred)", ArtistRole.HEADLINER) shouldBe
            listOf("Dosenstolz" to ArtistRole.HEADLINER, "Tancred" to ArtistRole.GUEST)
        roles("Lea (w/ Fynn)", ArtistRole.HEADLINER) shouldBe listOf("Lea" to ArtistRole.HEADLINER, "Fynn" to ArtistRole.GUEST)
    }

    @Test
    fun `an added act is support`() {
        roles("Warhammer (+ Corrode)", ArtistRole.HEADLINER) shouldBe
            listOf("Warhammer" to ArtistRole.HEADLINER, "Corrode" to ArtistRole.SUPPORT)
    }

    @Test
    fun `a DJ's guest keeps the DJ role`() {
        roles("DJ Storm (ft. J Majik)", ArtistRole.DJ) shouldBe listOf("DJ Storm" to ArtistRole.DJ, "J Majik" to ArtistRole.DJ)
    }
}
