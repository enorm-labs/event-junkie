package de.norm.events.slug

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.File

private val SEED_FILE = File("../http/importer/dev-seed.http")
private val MIGRATION_DIR = File("src/main/resources/db/migration")

private val VENUE_POST = Regex("""POST \{\{importer-host}}/api/admin/venues\s*""")
private val SLUG_PREDICATE = Regex("""slug\s*=\s*'([^']*)'|slug\s+IN\s*\(([^)]*)\)""", RegexOption.IGNORE_CASE)
private val QUOTED = Regex("'([^']*)'")

/**
 * Venue slugs that `dev-seed.http` no longer creates, mapped to the reason each is still allowed.
 *
 * A rename or a removal leaves an older migration naming a venue that is gone. The entry records
 * that, where a reviewer sees it.
 */
private val RETIRED_VENUE_SLUGS: Map<String, String> = emptyMap()

/**
 * Asserts that every `slug` literal in a migration names a venue the seed file creates.
 *
 * A guarded `UPDATE venue ... WHERE slug = '...'` with a misspelt slug updates no row. Flyway still
 * records the migration as applied, and nothing reports the row that stayed wrong (#987).
 */
class MigrationSlugTest {
    @Test
    fun `every slug in a migration names a venue`() {
        val seeded = seedVenueSlugs()
        val literals = migrationSlugLiterals()
        seeded.shouldNotBeEmpty()
        literals.shouldNotBeEmpty()

        val accepted = seeded + RETIRED_VENUE_SLUGS.keys
        literals
            .filterNot { it.slug in accepted }
            .map { "${it.file}:${it.line} '${it.slug}'" } shouldBe emptyList()
    }

    // A retired entry the seed file creates again, or that no migration names, is rot in the escape hatch.
    @Test
    fun `no retired slug is stale`() {
        val seeded = seedVenueSlugs()
        val named = migrationSlugLiterals().mapTo(mutableSetOf()) { it.slug }
        RETIRED_VENUE_SLUGS
            .filterKeys { it in seeded || it !in named }
            .map { (slug, reason) -> "$slug ($reason)" } shouldBe emptyList()
    }

    // A slug predicate ends at its own literal. The postal code in the same statement is not a slug.
    @Test
    fun `the scan reads slug predicates and nothing beside them`() {
        val sql =
            """
            UPDATE venue SET district = 'mitte'
            WHERE slug IN ('crack-bellmer', 'der-weisse-hase');
            UPDATE venue SET latitude = 52.5
            WHERE slug = 'amt' AND postal_code = '10437';
            """.trimIndent()

        slugLiteralsIn("V000__example.sql", sql) shouldBe
            listOf(
                SlugLiteral("V000__example.sql", 2, "crack-bellmer"),
                SlugLiteral("V000__example.sql", 2, "der-weisse-hase"),
                SlugLiteral("V000__example.sql", 4, "amt")
            )
    }
}

private data class SlugLiteral(
    val file: String,
    val line: Int,
    val slug: String
)

private fun seedVenueSlugs(): Set<String> = venueNamesIn(SEED_FILE.readText()).mapTo(mutableSetOf(), SlugGenerator::slugify)

private fun migrationSlugLiterals(): List<SlugLiteral> =
    MIGRATION_DIR
        .listFiles { file -> file.extension == "sql" }
        .orEmpty()
        .sortedBy { it.name }
        .flatMap { slugLiteralsIn(it.name, it.readText()) }

private fun slugLiteralsIn(
    file: String,
    sql: String
): List<SlugLiteral> =
    SLUG_PREDICATE
        .findAll(sql)
        .flatMap { match ->
            val line = sql.take(match.range.first).count { it == '\n' } + 1
            val slugs =
                if (match.groups[1] != null) {
                    sequenceOf(match.groupValues[1])
                } else {
                    QUOTED.findAll(match.groupValues[2]).map { it.groupValues[1] }
                }
            slugs.map { SlugLiteral(file, line, it) }
        }.toList()

/**
 * Reads the venue names out of `dev-seed.http`, which is where a venue exists before it exists
 * anywhere else (#876).
 *
 * Only the bodies that follow a venue POST count. An event source carries a `name` too, so a bare
 * search for that field would accept a source name as a venue slug.
 */
private fun venueNamesIn(http: String): List<String> {
    val mapper = JsonMapper.builder().build()
    val lines = http.lines()
    val names = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        if (!VENUE_POST.matches(lines[i])) {
            i++
            continue
        }
        i++
        while (i < lines.size && lines[i].isNotBlank()) {
            i++
        }
        val body = mutableListOf<String>()
        while (i < lines.size && !lines[i].startsWith("> {%") && !lines[i].startsWith("###")) {
            body.add(lines[i])
            i++
        }
        names.add(mapper.readTree(body.joinToString("\n")).get("name").asString())
    }
    return names
}
