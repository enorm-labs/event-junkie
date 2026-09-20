package de.norm.events.docs

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * The BFF's half of the swagger-ui pin (#491), a twin of `events-importer`'s
 * `SwaggerUiWebjarTest`: change both or neither. The constraint lives in each module's
 * `build.gradle.kts` separately, so dropping it from one would ship DOMPurify 3.4.12
 * (GHSA-55q2-fjhq-7xh7) while the other module's test passed. This is the cheap half, reading
 * the classpath only; the importer's twin also boots a context and fetches
 * `/webjars/swagger-ui/index.html`. `gradle.properties` has why the pin exists and what removes it.
 */
class SwaggerUiWebjarTest {
    @Test
    fun `the bundled DOMPurify is at or past the version that fixes GHSA-55q2-fjhq-7xh7`() {
        val found =
            PathMatchingResourcePatternResolver()
                .getResources("classpath*:/META-INF/resources/webjars/swagger-ui/*/swagger-ui-bundle.js")
        withClue(
            "expected exactly one swagger-ui-bundle.js on the runtime classpath, found ${found.size} — two webjars would " +
                "make which one springdoc serves a matter of classpath order"
        ) {
            found.size shouldBe 1
        }

        val version =
            Regex("""DOMPurify\.version\s*=\s*["']([0-9]+\.[0-9]+\.[0-9]+)["']""")
                .find(
                    found
                        .single()
                        .inputStream
                        .bufferedReader()
                        .readText()
                )?.groupValues
                ?.get(1)

        withClue("no DOMPurify version literal in the shipped swagger-ui bundle — has the bundle's build changed?") { version.shouldNotBeNull() }
        withClue(
            "the shipped swagger-ui bundle carries DOMPurify $version, which is affected by GHSA-55q2-fjhq-7xh7; " +
                "$FIXED_DOM_PURIFY or later is required. Has the swagger-ui constraint been dropped from events-bff?"
        ) {
            atLeast(version!!, FIXED_DOM_PURIFY) shouldBe true
        }
    }

    private companion object {
        /** DOMPurify's fix for GHSA-55q2-fjhq-7xh7. */
        const val FIXED_DOM_PURIFY = "3.4.13"

        /** Dotted-numeric comparison, because "3.4.9" is greater than "3.4.13" as a string. */
        fun atLeast(
            actual: String,
            minimum: String
        ): Boolean {
            val a = actual.split(".").map { it.toInt() }
            val m = minimum.split(".").map { it.toInt() }
            for (i in m.indices) {
                val left = a.getOrElse(i) { 0 }
                if (left != m[i]) return left > m[i]
            }
            return true
        }
    }
}
