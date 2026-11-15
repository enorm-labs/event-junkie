package de.norm.events.promoter

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PromoterKindTest {
    @Test
    fun `fromSlug resolves every kind by its slug`() {
        PromoterKind.entries.forEach { kind -> PromoterKind.fromSlug(kind.slug) shouldBe kind }
    }

    @Test
    fun `fromSlug is null for an unknown or absent slug`() {
        PromoterKind.fromSlug("unverified").shouldBeNull()
        PromoterKind.fromSlug(null).shouldBeNull()
    }

    @Test
    fun `slugs match the database constraint and the review file`() {
        // V251's CHECK and the `kind` column of docs/promoters/REVIEWED.tsv carry the same list.
        PromoterKind.entries.map { it.slug }.shouldContainExactly(
            "promoter",
            "collective",
            "label",
            "party",
            "media",
            "artist",
            "sponsor",
            "venue"
        )
    }
}
