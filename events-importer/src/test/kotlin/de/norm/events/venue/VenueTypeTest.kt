package de.norm.events.venue

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class VenueTypeTest {
    @Test
    fun `fromSlug resolves every type by its slug`() {
        VenueType.entries.forEach { type -> VenueType.fromSlug(type.slug) shouldBe type }
    }

    @Test
    fun `fromSlug is null for an unknown or absent slug`() {
        VenueType.fromSlug("stadium").shouldBeNull()
        VenueType.fromSlug(null).shouldBeNull()
    }

    @Test
    fun `slugs are unique and the frontend's order is the declaration order`() {
        // The frontend keeps the same list in `lib/venueTypes.ts`; this is the side to change first.
        VenueType.entries.map { it.slug }.shouldContainExactly(
            "club",
            "live-venue",
            "arena",
            "bar",
            "cultural-centre",
            "theatre",
            "cinema",
            "open-air",
            "gallery"
        )
    }
}
