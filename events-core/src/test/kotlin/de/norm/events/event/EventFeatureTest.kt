package de.norm.events.event

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class EventFeatureTest {
    @Test
    fun `fromSlug resolves every feature by its slug`() {
        EventFeature.entries.forEach { feature -> EventFeature.fromSlug(feature.slug) shouldBe feature }
    }

    @Test
    fun `fromSlug is null for an unknown or absent slug`() {
        EventFeature.fromSlug("diy-collective").shouldBeNull()
        EventFeature.fromSlug(null).shouldBeNull()
    }

    @Test
    fun `slugs are unique and the frontend's order is the declaration order`() {
        // `lib/eventFeatures.ts` type-checks against this enum through the OpenAPI document; the order it keeps by hand.
        EventFeature.entries.map { it.slug }.shouldContainExactly(
            "flinta-only",
            "queer",
            "sex-positive",
            "dress-code",
            "fetish-dress-code",
            "no-photo-policy",
            "open-end",
            "day-party",
            "open-stage",
            "for-children",
            "christmas-market"
        )
    }
}
