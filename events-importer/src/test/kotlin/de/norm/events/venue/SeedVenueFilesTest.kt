package de.norm.events.venue

import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val IMPORT_TRIGGER = Regex("""/api/admin/event-sources/([^/\s]+)/import""")

/**
 * The venue files and the generated "Run all" file agree with `SlugGenerator` (#2824).
 *
 * `scripts/seed_venues.py` names each file and each import trigger with a Python copy of the slug rule.
 * A copy can drift from Slugify, and a wrong trigger slug answers 404 for one venue in a run of hundreds.
 */
class SeedVenueFilesTest {
    @Test
    fun `every venue file is named for its venue's slug`() {
        val venues = seedVenues()
        venues.size shouldBeGreaterThan 100

        venues
            .filterNot { it.file.nameWithoutExtension == SlugGenerator.slugify(it.name) }
            .map { "${it.file.name}: '${it.name}' slugs to '${SlugGenerator.slugify(it.name)}'" }
            .shouldBeEmpty()
    }

    @Test
    fun `every import trigger in the run-all file names a seeded source's slug`() {
        val sourceSlugs = seedVenues().flatMap { it.sources }.map { SlugGenerator.slugify(it.get("name").asString()) }
        val triggers = IMPORT_TRIGGER.findAll(RUN_ALL_FILE.readText()).map { it.groupValues[1] }.toList()

        triggers.sorted() shouldBe sourceSlugs.sorted()
    }

    @Test
    fun `no source carries a venue id`() {
        seedVenues()
            .filter { venue -> venue.sources.any { it.has("venueId") } }
            .map { it.file.name }
            .shouldBeEmpty()
    }
}
