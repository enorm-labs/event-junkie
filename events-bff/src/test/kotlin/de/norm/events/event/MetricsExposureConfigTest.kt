package de.norm.events.event

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Asserts the main `application.yaml` exposes `prometheus`, by reading the file. A twin of
 * `events-importer`'s `MetricsExposureConfigTest`: change both or neither. Neither can see the
 * chart, the copy that outranks all four at runtime; `invariants_test.yaml` fails the build if
 * the chart sets `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` at all.
 *
 * `src/test/resources/application.yaml` shadows the main file, so an integration test hitting
 * `/actuator/prometheus` proves nothing about the shipped configuration: delete the line from
 * the main file and every test passes while the scrape target 404s in production. Both files are
 * checked, because the pair drifting apart is how a test starts lying.
 */
class MetricsExposureConfigTest {
    private fun exposureLine(path: String): String {
        val file = File(path)
        withClue("expected $path to exist — has the module layout moved?") { file.exists() shouldBe true }
        return file
            .readLines()
            .firstOrNull { it.trimStart().startsWith("include:") }
            ?: error("no `include:` line in $path — the actuator exposure list has moved or been removed")
    }

    @Test
    fun `the shipped configuration exposes the prometheus endpoint`() {
        val line = exposureLine("src/main/resources/application.yaml")

        withClue(
            "the main application.yaml must expose `prometheus` or nothing can scrape this service; found: $line"
        ) {
            line.contains("prometheus") shouldBe true
        }
    }

    @Test
    fun `the test configuration exposes the same endpoints as the shipped one`() {
        val main = exposureLine("src/main/resources/application.yaml").substringAfter("include:").trim()
        val test = exposureLine("src/test/resources/application.yaml").substringAfter("include:").trim()

        withClue(
            "the test application.yaml shadows the main one, so the two exposure lists must match " +
                "or the tests are exercising a configuration that is never shipped.\n  main: $main\n  test: $test"
        ) {
            (main == test) shouldBe true
        }
    }
}
