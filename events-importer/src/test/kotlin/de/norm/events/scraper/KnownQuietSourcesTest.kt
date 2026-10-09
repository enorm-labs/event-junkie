package de.norm.events.scraper

import de.norm.events.slug.SlugGenerator
import de.norm.events.venue.seedVenues
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The slug of each event source the seed venue files create, which is what the gauges carry. */
private fun seededSourceSlugs(): Set<String> = seedVenues().flatMap { it.sources }.mapTo(mutableSetOf()) { SlugGenerator.slugify(it.get("name").asString()) }

/**
 * A misspelt slug in [KNOWN_QUIET_SOURCES] or [KNOWN_BLOCKED_SOURCES] marks nothing, and the rule
 * keeps naming the source it was meant to excuse — the same way a wrong slug in a migration fails open (#987).
 */
class KnownQuietSourcesTest {
    @Test
    fun `every known-quiet slug names a seeded source`() {
        val seeded = seededSourceSlugs()
        seeded.shouldNotBeEmpty()

        KNOWN_QUIET_SOURCES.keys.filterNot { it in seeded } shouldBe emptyList()
    }

    @Test
    fun `every known-blocked slug names a seeded source`() {
        val seeded = seededSourceSlugs()

        KNOWN_BLOCKED_SOURCES.keys.filterNot { it in seeded } shouldBe emptyList()
    }
}
