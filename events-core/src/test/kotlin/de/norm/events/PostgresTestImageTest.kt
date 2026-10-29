package de.norm.events

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Keeps [POSTGRES_TEST_IMAGE] the one place a test names its PostgreSQL image (#4222).
 *
 * Dependabot's `docker-compose` updater bumps `compose.yaml` and nothing else, so the first check
 * turns its pull request red until the constant moves in the same change. The second stops a new
 * migration test from pinning a literal tag that no updater and no check would ever see again.
 */
class PostgresTestImageTest {
    private fun repoRoot(): File {
        val root = File("..").absoluteFile.normalize()
        withClue("expected the repository root at $root — this test reads compose.yaml and the sibling modules") {
            File(root, "settings.gradle.kts").isFile shouldBe true
        }
        return root
    }

    @Test
    fun `the test image is the compose image`() {
        val compose = File(repoRoot(), "compose.yaml").readText()
        val composeImage = COMPOSE_POSTGRES_IMAGE.find(compose)?.groupValues?.get(1)
        withClue("compose.yaml moved its postgres image; set POSTGRES_TEST_IMAGE in events-core's test fixtures to the same tag") {
            composeImage shouldBe POSTGRES_TEST_IMAGE
        }
    }

    @Test
    fun `no other test source names a postgres tag`() {
        val root = repoRoot()
        val sourceRoots = MODULES.map { File(root, "$it/src") }
        sourceRoots.forEach { withClue("expected sources at $it") { it.isDirectory shouldBe true } }
        val offenders =
            sourceRoots
                .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }
                .filterNot { it.name == DEFINITION_FILE }
                .filter { POSTGRES_TAG_LITERAL.containsMatchIn(it.readText()) }
                .map { it.relativeTo(root).path }
        withClue("these files pin a postgres image of their own; use POSTGRES_TEST_IMAGE") { offenders.shouldBeEmpty() }
    }

    private companion object {
        val MODULES = listOf("events-core", "events-bff", "events-importer")
        const val DEFINITION_FILE = "PostgresTestImage.kt"
        val COMPOSE_POSTGRES_IMAGE = Regex("""(?m)^\s+image:\s*['"]?(postgres:[^'"\s]+)""")
        val POSTGRES_TAG_LITERAL = Regex(""""postgres:\d""")
    }
}
