package de.norm.events

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.FileSystemResource

/**
 * Asserts what `/actuator/health/readiness` is composed of, by reading the configuration files.
 *
 * This is the same shape as [de.norm.events.event.MetricsExposureConfigTest] and exists for the same
 * reason: `src/test/resources/application.yaml` **shadows** the main file rather than merging with
 * it, so an integration test hitting the readiness endpoint proves the group works *as configured for
 * tests* and proves nothing about the group that ships. Deleting the readiness group from the main
 * file leaves every other test green while the deployed BFF goes back to reporting Ready before it
 * can serve — which is [#438](https://github.com/enorm-labs/event-junkie/issues/438) reopening itself
 * silently.
 *
 * The files are read through [YamlPropertySourceLoader], Spring's own loader, so this cannot disagree
 * with runtime parsing the way line-matching would.
 */
class ReadinessGroupConfigTest {
    @Test
    fun `the shipped readiness group includes both database indicators`() {
        val readiness = probeGroup(MAIN_CONFIG, "readiness")

        withClue(
            "readiness must mean `can serve`, not `Spring started` (#438, ADR-018). `r2dbc` proves the " +
                "database is reachable and `eventsSchema` proves the schema is migrated and readable; " +
                "neither alone closes the window #263 measured. Found: $readiness"
        ) {
            readiness shouldBe setOf("readinessState", "r2dbc", "eventsSchema")
        }
    }

    @Test
    fun `liveness never depends on the database`() {
        val liveness = probeGroup(MAIN_CONFIG, "liveness")

        withClue(
            "a database-dependent liveness probe restarts every replica during a database outage and " +
                "makes recovery slower than the outage (ADR-018). Found: $liveness"
        ) {
            liveness shouldBe setOf("livenessState")
        }
    }

    @Test
    fun `the test configuration declares the same probe groups as the shipped one`() {
        for (group in listOf("readiness", "liveness")) {
            val main = probeGroup(MAIN_CONFIG, group)
            val test = probeGroup(TEST_CONFIG, group)

            withClue(
                "the test application.yaml shadows the main one, so the `$group` group must match or " +
                    "the tests are exercising probe semantics that are never shipped.\n  main: $main\n  test: $test"
            ) {
                test shouldBe main
            }
        }
    }

    @Test
    fun `the health component name still matches the indicator class`() {
        val derived =
            EventsSchemaHealthIndicator::class
                .simpleName!!
                .removeSuffix("HealthIndicator")
                .replaceFirstChar { it.lowercase() }

        withClue(
            "Spring derives the health component name from the bean name with the `HealthIndicator` " +
                "suffix removed, so renaming the class renames the component. `$derived` is not in the " +
                "readiness group — the context would fail to start on group-membership validation."
        ) {
            (derived in probeGroup(MAIN_CONFIG, "readiness")) shouldBe true
        }
    }

    private fun probeGroup(
        path: String,
        group: String
    ): Set<String> {
        val resource = FileSystemResource(path)
        withClue("expected $path to exist — has the module layout moved?") { resource.exists() shouldBe true }

        val key = "management.endpoint.health.group.$group.include"
        val value =
            YamlPropertySourceLoader()
                .load(path, resource)
                .firstNotNullOfOrNull { it.getProperty(key) }
                ?: error("`$key` is not set in $path — the probe groups have been removed or renamed")

        return value
            .toString()
            .split(",")
            .map { it.trim() }
            .toSet()
    }

    private companion object {
        const val MAIN_CONFIG = "src/main/resources/application.yaml"
        const val TEST_CONFIG = "src/test/resources/application.yaml"
    }
}
