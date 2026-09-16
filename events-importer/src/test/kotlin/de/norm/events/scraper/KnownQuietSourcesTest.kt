package de.norm.events.scraper

import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

private val SEED_FILE = File("../http/importer/dev-seed.http")

/** The `name` of each event source the seed creates; its slug is what the gauges carry. */
private val SOURCE_NAME = Regex("""POST \{\{importer-host}}/api/admin/event-sources[\s\S]*?"name"\s*:\s*"([^"]+)"""")

/**
 * A misspelt slug in [KNOWN_QUIET_SOURCES] marks nothing, and the rule keeps naming the source
 * it was meant to excuse — the same way a wrong slug in a migration fails open (#987).
 */
class KnownQuietSourcesTest {
    @Test
    fun `every known-quiet slug names a seeded source`() {
        val seeded = SOURCE_NAME.findAll(SEED_FILE.readText()).map { SlugGenerator.slugify(it.groupValues[1]) }.toSet()
        seeded.shouldNotBeEmpty()

        KNOWN_QUIET_SOURCES.keys.filterNot { it in seeded } shouldBe emptyList()
    }
}
