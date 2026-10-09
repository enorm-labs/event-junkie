package de.norm.events.slug

import de.norm.events.venue.seedVenues
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

private val MIGRATION_DIR = File("src/main/resources/db/migration")

private val SLUG_PREDICATE = Regex("""slug\s*=\s*'([^']*)'|slug\s+IN\s*\(([^)]*)\)""", RegexOption.IGNORE_CASE)
private val QUOTED = Regex("'([^']*)'")
private val STATEMENT_TABLE = Regex("""\b(?:UPDATE|DELETE\s+FROM|INSERT\s+INTO)\s+(\w+)""", RegexOption.IGNORE_CASE)

/**
 * Venue slugs that the seed venue files no longer create, mapped to the reason each is still allowed.
 *
 * A rename or a removal leaves an older migration naming a venue that is gone. The entry records
 * that, where a reviewer sees it.
 */
private val RETIRED_VENUE_SLUGS: Map<String, String> =
    mapOf("arkaoda" to "the club closed on 2026-08-30; V051 removes the venue and the seed file no longer creates it (#1788)")

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

    // A slug predicate ends at its own literal; the postal code in the same statement is not a slug,
    // and a promoter statement is not read at all.
    @Test
    fun `the scan reads venue slug predicates and nothing beside them`() {
        val sql =
            """
            UPDATE venue SET district = 'mitte'
            WHERE slug IN ('crack-bellmer', 'der-weisse-hase');
            UPDATE venue SET latitude = 52.5
            WHERE slug = 'amt' AND postal_code = '10437';
            DELETE FROM promoter WHERE slug IN ('act', 'bum');
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

/** The seed venue files are where a venue exists before it exists anywhere else (#876, #2824). */
private fun seedVenueSlugs(): Set<String> = seedVenues().mapTo(mutableSetOf()) { SlugGenerator.slugify(it.name) }

private fun migrationSlugLiterals(): List<SlugLiteral> =
    MIGRATION_DIR
        .listFiles { file -> file.extension == "sql" }
        .orEmpty()
        .sortedBy { it.name }
        .flatMap { slugLiteralsIn(it.name, it.readText()) }

/**
 * Only a statement on `venue` is read. A promoter migration (V023, V025, V026) names slugs no
 * seed file creates; `MergeDuplicatePromotersMigrationTest` runs those against planted rows instead.
 */
private fun slugLiteralsIn(
    file: String,
    sql: String
): List<SlugLiteral> =
    SLUG_PREDICATE
        .findAll(sql)
        .filter { match -> STATEMENT_TABLE.find(sql.substring(sql.lastIndexOf(';', match.range.first) + 1, match.range.first))?.groupValues?.get(1) == "venue" }
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
