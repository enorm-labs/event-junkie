package de.norm.events.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * The BFF's half of the swagger-ui pin (#491).
 *
 * **A deliberate twin of `events-importer`'s `SwaggerUiWebjarTest` — change both or neither.** The
 * constraint that holds `org.webjars:swagger-ui` ahead of springdoc lives in each module's
 * `build.gradle.kts` separately, because each module resolves its own runtime classpath. So a change
 * that dropped it from one and not the other would leave that module shipping a bundle with
 * DOMPurify 3.4.12 — GHSA-55q2-fjhq-7xh7 — while the other module's test went on passing. A
 * module-scoped defect needs a module-scoped test.
 *
 * This one is deliberately the **cheap** half: it reads the classpath and nothing else. The
 * importer's twin additionally boots a context and fetches `/webjars/swagger-ui/index.html`, because
 * springdoc resolves the webjar's versioned resource path and that is the one thing moving the
 * version could break — proving it once is enough, and both modules use the same springdoc starter.
 *
 * See `gradle.properties` for why the pin exists and what removes it.
 */
class SwaggerUiWebjarTest {
    @Test
    fun `the bundled DOMPurify is at or past the version that fixes GHSA-55q2-fjhq-7xh7`() {
        val found =
            PathMatchingResourcePatternResolver()
                .getResources("classpath*:/META-INF/resources/webjars/swagger-ui/*/swagger-ui-bundle.js")
        assertEquals(1, found.size) {
            "expected exactly one swagger-ui-bundle.js on the runtime classpath, found ${found.size} — two webjars would " +
                "make which one springdoc serves a matter of classpath order"
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

        assertNotNull(version) { "no DOMPurify version literal in the shipped swagger-ui bundle — has the bundle's build changed?" }
        assertTrue(atLeast(version!!, FIXED_DOM_PURIFY)) {
            "the shipped swagger-ui bundle carries DOMPurify $version, which is affected by GHSA-55q2-fjhq-7xh7; " +
                "$FIXED_DOM_PURIFY or later is required. Has the swagger-ui constraint been dropped from events-bff?"
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
