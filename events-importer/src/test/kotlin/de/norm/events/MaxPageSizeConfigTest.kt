package de.norm.events

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Asserts `app.api.max-page-size` is set in the **shipped** `application.yaml`, by reading the file.
 *
 * **A deliberate twin of `events-bff`'s test — change both or neither.** The resolver is duplicated
 * per module because `events-core` carries no web dependencies, so the cap is set twice and can be
 * dropped from one file alone.
 *
 * `src/test/resources/application.yaml` shadows the main file rather than merging with it, so a test
 * asserting the clamp proves it works *when set* and proves nothing about the configuration that
 * ships. Delete the property from the main file and every other test here still passes, the
 * application still starts, and `GET /api/admin/events?size=2000` returns 2000 rows.
 */
class MaxPageSizeConfigTest {
    private fun maxPageSize(path: String): String {
        val file = File(path)
        withClue("expected $path to exist — has the module layout moved?") { file.exists() shouldBe true }
        return file
            .readLines()
            .firstOrNull { it.trimStart().startsWith("max-page-size:") }
            ?.substringAfter("max-page-size:")
            ?.trim()
            ?: error("no `max-page-size:` line in $path — the public API's page cap has been removed")
    }

    @Test
    fun `the shipped configuration caps the page size`() {
        val shipped = maxPageSize("src/main/resources/application.yaml")

        withClue("app.api.max-page-size must be a positive number; found: $shipped") {
            (shipped.toIntOrNull()?.let { it > 0 } ?: false) shouldBe true
        }
    }

    @Test
    fun `the test configuration caps the page size at the same number as the shipped one`() {
        val main = maxPageSize("src/main/resources/application.yaml")
        val test = maxPageSize("src/test/resources/application.yaml")

        withClue(
            "the test application.yaml shadows the main one, so the two caps must match or the " +
                "tests are exercising a limit that is never shipped.\n  main: $main\n  test: $test"
        ) {
            (main == test) shouldBe true
        }
    }
}
