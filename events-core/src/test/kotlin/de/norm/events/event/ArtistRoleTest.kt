package de.norm.events.event

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ArtistRoleTest {
    @Test
    fun `parseOrDefault returns HEADLINER for exact match`() {
        ArtistRole.parseOrDefault("HEADLINER") shouldBe ArtistRole.HEADLINER
    }

    @Test
    fun `parseOrDefault is case-insensitive`() {
        ArtistRole.parseOrDefault("headliner") shouldBe ArtistRole.HEADLINER
        ArtistRole.parseOrDefault("Support") shouldBe ArtistRole.SUPPORT
        ArtistRole.parseOrDefault("dj") shouldBe ArtistRole.DJ
    }

    @Test
    fun `parseOrDefault trims whitespace`() {
        ArtistRole.parseOrDefault("  SUPPORT  ") shouldBe ArtistRole.SUPPORT
        ArtistRole.parseOrDefault("\tDJ\n") shouldBe ArtistRole.DJ
    }

    @Test
    fun `parseOrDefault returns all valid enum values`() {
        ArtistRole.parseOrDefault("HEADLINER") shouldBe ArtistRole.HEADLINER
        ArtistRole.parseOrDefault("SUPPORT") shouldBe ArtistRole.SUPPORT
        ArtistRole.parseOrDefault("DJ") shouldBe ArtistRole.DJ
    }

    @Test
    fun `parseOrDefault returns HEADLINER for unknown values`() {
        ArtistRole.parseOrDefault("UNKNOWN") shouldBe ArtistRole.HEADLINER
        ArtistRole.parseOrDefault("vocalist") shouldBe ArtistRole.HEADLINER
        ArtistRole.parseOrDefault("") shouldBe ArtistRole.HEADLINER
    }
}
