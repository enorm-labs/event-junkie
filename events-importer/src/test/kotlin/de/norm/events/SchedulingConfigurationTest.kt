package de.norm.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.io.File

/**
 * Guards the two halves of [SchedulingConfiguration]: that it is on by default, and off in this suite.
 *
 * **Neither half is testable from an integration test, which is why this uses [ApplicationContextRunner].**
 * `src/test/resources/application.yaml` shadows the main file, so every `@SpringBootTest` in this module
 * runs with `app.scheduling.enabled=false` and can say nothing about the default. Forking a context with
 * `properties = ["app.scheduling.enabled=true"]` to ask would cost a second cached Spring context holding
 * a second R2DBC pool — which is the shape of the problem this class exists to fix.
 *
 * The runner starts no database and caches nothing.
 *
 * The failure this closes is quiet in both directions. A `matchIfMissing = false` slip stops production
 * imports while every test stays green, because the tests want scheduling off. A test `application.yaml`
 * that loses the property brings back #949: gauge refreshers firing at context refresh, against the
 * tables `BaseControllerTest.cleanUp` is truncating, which deadlocks one arbitrary test per run.
 */
class SchedulingConfigurationTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(SchedulingConfiguration::class.java)

    @Test
    fun `scheduling is on when nothing sets the property`() {
        runner.run { context ->
            assertThat(context).hasSingleBean(SchedulingConfiguration::class.java)
        }
    }

    @Test
    fun `scheduling is off when the property is false`() {
        runner.withPropertyValues("app.scheduling.enabled=false").run { context ->
            assertThat(context).doesNotHaveBean(SchedulingConfiguration::class.java)
        }
    }

    @Test
    fun `the test configuration switches scheduling off for the whole suite`() {
        val yaml = File("src/test/resources/application.yaml")
        assertThat(yaml).exists()

        assertThat(yaml.readText())
            .`as`(
                "the importer test suite must run with scheduling off (#949): a @Scheduled task fires once at " +
                    "context refresh whatever its interval says, and a gauge query racing cleanUp's TRUNCATE " +
                    "deadlocks one arbitrary test per run"
            ).contains("enabled: false")
    }
}
