package de.norm.events

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.test.Test

/**
 * The enforcement ADR-004 never had (#540).
 *
 * ADR-004 required custom `@Query` SQL to carry the `events.` prefix and listed *"developers must
 * remember the `events.` prefix"* under Negative — enforcement by memory, which nothing checked.
 * These are the checks that make [EVENTS_SCHEMA] a real single source of truth instead, each phrased
 * so it fails on the change that would reintroduce the split:
 *
 * 1. **No hand-written statement may name a schema literally.** A new `@Query` written with
 *    `events.` still works today and would quietly become the eighth place the name is hardcoded.
 * 2. **Every `application.yaml` must declare the same name.** `spring.flyway.schemas` creates the
 *    schema and `spring.r2dbc.properties.schema` sets the connection's `search_path`; neither can
 *    read a Kotlin constant, so they are declarations that have to agree — including the *test*
 *    copies, which shadow rather than merge and would otherwise let the suite pass against a schema
 *    that is never shipped.
 * 3. **Migration SQL must stay unqualified.** Flyway sets `search_path` from `spring.flyway.schemas`
 *    before running it, so qualifying a migration would pin it to a schema the rest of the
 *    configuration no longer controls.
 *
 * It lives in `events-core`, where the constant it defends lives, and reads the sibling modules'
 * sources: the invariant covers all raw SQL in both applications, and a per-module copy would be two
 * tests that each see half of it. The standard Gradle layout is asserted rather than trusted — a
 * module that moved would otherwise make every check below pass by scanning nothing.
 */
class SchemaConfigurationTest {
    /**
     * `FROM events.x`, `JOIN events.x`, `UPDATE events.x` and friends, in Kotlin source.
     *
     * Anchored on the SQL keyword so it cannot match a package name or a log line — `de.norm.events.`
     * and `${'$'}{result.events.size}` both appear in this tree and neither is a schema reference.
     */
    private val literalSchemaInSql =
        Regex("""\b(FROM|JOIN|INTO|UPDATE|DELETE\s+FROM)\s+$EVENTS_SCHEMA\.""", RegexOption.IGNORE_CASE)

    private fun repoRoot(): File {
        val root = File("..").absoluteFile.normalize()
        withClue(
            "expected the repository root at $root — this test scans sideways into the sibling modules " +
                "and would silently check nothing if the layout moved"
        ) {
            File(root, "settings.gradle.kts").isFile shouldBe true
        }
        return root
    }

    private fun mainSources(): List<File> {
        val modules = MODULES.map { File(repoRoot(), "$it/src/main/kotlin") }
        modules.forEach { withClue("expected Kotlin sources at $it") { it.isDirectory shouldBe true } }
        val sources = modules.flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }
        withClue("only ${sources.size} Kotlin files found — the scan below would prove little") { (sources.size > MIN_SOURCE_FILES) shouldBe true }
        return sources
    }

    @Test
    fun `no hand-written statement names the schema literally`() {
        val offenders =
            mainSources()
                .filter { literalSchemaInSql.containsMatchIn(it.readText()) }
                .map { it.relativeTo(repoRoot()).path }

        withClue(
            "these files name the schema as a literal in SQL instead of interpolating EVENTS_SCHEMA:\n" +
                offenders.joinToString("\n") { "  - $it" } +
                "\n\nA Kotlin `const val` is usable inside an annotation, so even a @Query can carry it — which is " +
                "the whole reason the constant exists rather than a property (#540)."
        ) {
            offenders shouldBe emptyList()
        }
    }

    /**
     * The positive half, so the check above cannot pass because the SQL was deleted or the regex
     * stopped matching anything at all. An assertion that cannot fail is worse than no assertion,
     * because it is counted.
     */
    @Test
    fun `the constant is actually interpolated into raw SQL, so the check above has something to guard`() {
        val users = mainSources().count { it.readText().contains("\$EVENTS_SCHEMA.") }

        withClue(
            "only $users file(s) interpolate EVENTS_SCHEMA into SQL, expected at least $EXPECTED_RAW_SQL_FILES. " +
                "Either the raw SQL moved, or this guard is now watching nothing."
        ) {
            (users >= EXPECTED_RAW_SQL_FILES) shouldBe true
        }
    }

    @Test
    fun `every application yaml declares the same schema the code resolves`() {
        val declaration = Regex("""^\s*schemas?:\s*(\S+)\s*$""", RegexOption.MULTILINE)
        val yamls =
            MODULES
                .flatMap { module ->
                    listOf("src/main/resources/application.yaml", "src/test/resources/application.yaml")
                        .map { File(repoRoot(), "$module/$it") }
                }.filter { it.isFile }

        withClue(
            "found only ${yamls.size} application.yaml files, expected at least $EXPECTED_YAML_FILES"
        ) {
            (yamls.size >= EXPECTED_YAML_FILES) shouldBe true
        }

        val wrong =
            yamls.flatMap { file ->
                declaration
                    .findAll(file.readText())
                    .map { file.relativeTo(repoRoot()).path to it.groupValues[1] }
                    .filter { (_, value) -> value != EVENTS_SCHEMA }
                    .toList()
            }

        withClue(
            "these declarations name a schema the code does not resolve to ('$EVENTS_SCHEMA'):\n" +
                wrong.joinToString("\n") { (file, value) -> "  - $file -> $value" } +
                "\n\nThe application would start cleanly and query a schema Flyway never created."
        ) {
            wrong shouldBe emptyList()
        }
    }

    /**
     * The other direction, and the one place ADR-004's original sentence was right.
     */
    @Test
    fun `migration SQL stays unqualified, because Flyway sets the search_path`() {
        val migrations = File(repoRoot(), "events-importer/src/main/resources/db/migration")
        withClue("expected the migrations at $migrations") { migrations.isDirectory shouldBe true }

        val files = migrations.listFiles { f: File -> f.extension == "sql" }?.toList().orEmpty()
        withClue("no migrations found — this check would prove nothing") { files.isNotEmpty() shouldBe true }

        val qualified =
            files
                .filter { literalSchemaInSql.containsMatchIn(it.readText()) }
                .map { it.name }

        withClue(
            "these migrations qualify their tables with the schema name: $qualified. Flyway sets the " +
                "search_path from spring.flyway.schemas before running them, so qualifying pins a migration " +
                "to a schema the configuration no longer controls (ADR-004)."
        ) {
            qualified shouldBe emptyList()
        }
    }

    private companion object {
        val MODULES = listOf("events-bff", "events-importer")

        /**
         * The five files carrying raw SQL today: the BFF's `EventSearchRepository`, and the
         * importer's `EventSourceRepository`, `ArtistRepository`, `PromoterRepository` and
         * `GenreTagRepositories`. A floor rather than an equality, so adding legitimate raw SQL does
         * not fail the build — the point is only that the guard is watching live code.
         */
        const val EXPECTED_RAW_SQL_FILES = 5

        /** Two modules × (main, test). */
        const val EXPECTED_YAML_FILES = 4

        /** A floor on the scan itself: this tree holds several hundred Kotlin files under `src/main`. */
        const val MIN_SOURCE_FILES = 100
    }
}
