package de.norm.events

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The version the build embeds is the highest migration file, so the readiness gate waits for the
 * schema this build actually reads (#3384).
 */
class ExpectedSchemaVersionTest {
    @Test
    fun `the embedded expected version is the highest importer migration`() {
        val directory = File(System.getProperty("spring.flyway.locations").removePrefix("filesystem:"))
        val highest =
            directory
                .listFiles()
                .orEmpty()
                .mapNotNull {
                    Regex("""^V(\d+)__""")
                        .find(it.name)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt()
                }.max()

        withClue("`generateExpectedSchemaVersion` in events-bff/build.gradle.kts disagrees with $directory") {
            SchemaVersionHealthIndicator.readExpectedVersion() shouldBe highest
        }
    }
}
