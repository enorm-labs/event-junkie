package de.norm.events.event

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Asserts the **main** `application.yaml` exposes `prometheus`, by reading the file.
 *
 * This looks like testing configuration for its own sake, and it is not. `src/test/resources/
 * application.yaml` **shadows** the main file rather than merging with it, so every Spring test in
 * this module runs against the test copy — which means an integration test hitting
 * `/actuator/prometheus` proves the endpoint works *when exposed*, and proves nothing at all about
 * whether the shipped configuration exposes it.
 *
 * The failure that leaves open is the quiet one: delete the line from the main file and every test
 * here still passes, the application still starts, every endpoint still works, and the scrape target
 * 404s in production. The first symptom is an empty dashboard, found whenever somebody next looks.
 *
 * Both files are checked, because the pair drifting apart is the other half of the same problem: the
 * test config silently doing something the real one does not is how a test starts lying.
 */
class MetricsExposureConfigTest {
    private fun exposureLine(path: String): String {
        val file = File(path)
        assertTrue(file.exists()) { "expected $path to exist — has the module layout moved?" }
        return file
            .readLines()
            .firstOrNull { it.trimStart().startsWith("include:") }
            ?: error("no `include:` line in $path — the actuator exposure list has moved or been removed")
    }

    @Test
    fun `the shipped configuration exposes the prometheus endpoint`() {
        val line = exposureLine("src/main/resources/application.yaml")

        assertTrue(line.contains("prometheus")) {
            "the main application.yaml must expose `prometheus` or nothing can scrape this service; found: $line"
        }
    }

    @Test
    fun `the test configuration exposes the same endpoints as the shipped one`() {
        val main = exposureLine("src/main/resources/application.yaml").substringAfter("include:").trim()
        val test = exposureLine("src/test/resources/application.yaml").substringAfter("include:").trim()

        assertTrue(main == test) {
            "the test application.yaml shadows the main one, so the two exposure lists must match " +
                "or the tests are exercising a configuration that is never shipped.\n  main: $main\n  test: $test"
        }
    }
}
