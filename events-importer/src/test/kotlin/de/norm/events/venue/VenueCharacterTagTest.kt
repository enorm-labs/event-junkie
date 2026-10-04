package de.norm.events.venue

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class VenueCharacterTagTest {
    @Test
    fun `fromSlug resolves every tag by its slug`() {
        VenueCharacterTag.entries.forEach { tag -> VenueCharacterTag.fromSlug(tag.slug) shouldBe tag }
    }

    @Test
    fun `fromSlug is null for an unknown or absent slug`() {
        VenueCharacterTag.fromSlug("open-air").shouldBeNull()
        VenueCharacterTag.fromSlug(null).shouldBeNull()
    }

    @Test
    fun `slugs are unique and the frontend's order is the declaration order`() {
        // The frontend keeps the same list in `lib/venueCharacters.ts`; this is the side to change first.
        VenueCharacterTag.entries.map { it.slug }.shouldContainExactly(
            "queer",
            "sex-positive",
            "diy-collective",
            "awareness-team",
            "safer-space-policy",
            "quiet-room",
            "all-gender-toilets",
            "free-water",
            "smoke-free",
            "dress-code",
            "fetish-dress-code",
            "no-photo-policy",
            "cash-only",
            "wheelchair-accessible"
        )
    }
}
