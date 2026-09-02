package de.norm.events.meta

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.info.BuildProperties
import java.util.Properties

/**
 * The mapping is a pure function, so it is unit-tested directly rather than through the endpoint.
 * The absent-`BuildProperties` case in particular cannot be reached from an integration test: it
 * depends on whether the build stamped `build-info.properties`, which is not the test's to control.
 */
class MetaResponseTest {
    private fun buildProperties(vararg entries: Pair<String, String>): BuildProperties =
        BuildProperties(Properties().apply { entries.forEach { (key, value) -> setProperty(key, value) } })

    @Test
    fun `reports the stamped version and derives the short commit from the full sha`() {
        val response =
            MetaResponse.from(
                buildProperties(
                    "version" to "0.1.0",
                    "commit" to "9f1a2b3c4d5e6f708192a3b4c5d6e7f809a1b2c3"
                )
            )

        response.version shouldBe "0.1.0"
        response.commit shouldBe "9f1a2b3c4d5e6f708192a3b4c5d6e7f809a1b2c3"
        response.commitShort shouldBe "9f1a2b3"
    }

    @Test
    fun `reports the build time when the build stamped one`() {
        val response = MetaResponse.from(buildProperties("version" to "0.1.0", "time" to "2026-08-07T12:49:19Z"))

        response.buildTime shouldBe "2026-08-07T12:49:19Z"
    }

    @Test
    fun `omits the build time when the build stamped none`() {
        MetaResponse.from(buildProperties("version" to "0.1.0")).buildTime shouldBe null
    }

    @Test
    fun `falls back to dev when the build stamped nothing`() {
        // The IDE and `bootRun` case: no build-info.properties, so no BuildProperties bean. The
        // footer must still render something rather than an empty gap.
        val response = MetaResponse.from(null)

        response.version shouldBe MetaResponse.DEV_VERSION
        response.commit shouldBe null
        response.commitShort shouldBe null
    }

    @Test
    fun `treats the unknown commit placeholder as no commit at all`() {
        // The root build stamps "unknown" when there is no git metadata (a source-tarball build).
        // Passing that through would render a footer link to /commit/unknown, which 404s.
        val response = MetaResponse.from(buildProperties("version" to "0.1.0", "commit" to "unknown"))

        response.version shouldBe "0.1.0"
        response.commit shouldBe null
        response.commitShort shouldBe null
    }

    @Test
    fun `treats a blank commit as no commit`() {
        val response = MetaResponse.from(buildProperties("version" to "0.1.0", "commit" to "   "))

        response.commit shouldBe null
        response.commitShort shouldBe null
    }

    @Test
    fun `keeps the version when the build stamped no commit`() {
        val response = MetaResponse.from(buildProperties("version" to "0.1.0"))

        response.version shouldBe "0.1.0"
        response.commit shouldBe null
    }
}
