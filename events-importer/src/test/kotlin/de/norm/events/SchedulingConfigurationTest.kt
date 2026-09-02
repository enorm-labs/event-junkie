package de.norm.events

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
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

    // Spring Boot's `hasSingleBean` is an AssertJ extension on the context, with no Kotest
    // equivalent (#946). Reading the names directly costs one thing worth keeping: on a context
    // that failed to start it throws naming only the exception *type*, where AssertJ reported the
    // cause. Checking `startupFailure` first puts the cause back, chained.
    private fun AssertableApplicationContext.beans(): List<String> {
        startupFailure?.let { throw AssertionError("the application context failed to start", it) }
        return getBeanNamesForType(SchedulingConfiguration::class.java).toList()
    }

    @Test
    fun `scheduling is on when nothing sets the property`() {
        runner.run { context ->
            context.beans() shouldHaveSize 1
        }
    }

    @Test
    fun `scheduling is off when the property is false`() {
        runner.withPropertyValues("app.scheduling.enabled=false").run { context ->
            context.beans().shouldBeEmpty()
        }
    }

    @Test
    fun `the test configuration switches scheduling off for the whole suite`() {
        val yaml = File("src/test/resources/application.yaml")
        yaml.shouldExist()

        withClue(
            "the importer test suite must run with scheduling off (#949): a @Scheduled task fires once at " +
                "context refresh whatever its interval says, and a gauge query racing cleanUp's TRUNCATE " +
                "deadlocks one arbitrary test per run"
        ) {
            yaml.readText() shouldContain "enabled: false"
        }
    }
}
