package de.norm.events.docs

import de.norm.events.BaseControllerTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * Holds the Swagger UI webjar ahead of springdoc, and proves the UI still works when it is (#491).
 *
 * OWASP Dependency-Check raises GHSA-55q2-fjhq-7xh7 against the JavaScript bundled inside the
 * `swagger-ui` webjar: DOMPurify's `IN_PLACE` hook removal leaves a detached subtree executable,
 * causing XSS. Affects DOMPurify <= 3.4.12, fixed in 3.4.13. The chain is
 * `springdoc-openapi-starter-webflux-ui:3.1.0` → `org.webjars:swagger-ui:5.32.11` →
 * `swagger-ui-bundle.js`, and springdoc 3.1.0 is its latest release, so there is nothing to bump on
 * that side. Pinning the webjar ahead of springdoc is safe on its own — 5.32.13 is two patch
 * releases inside the same 5.32.x line — which beats suppressing the finding.
 *
 * **Why a test rather than the constraint alone.** A dependency constraint is silent when it stops
 * applying. springdoc shipping a release that names 5.32.13 or later makes it a harmless no-op, and
 * that direction is fine; a change that drops the constraint, or a webjar that regresses, would put
 * a vulnerable bundle back in the image with nothing saying so. This reads the bytes that ship.
 *
 * It deliberately does **not** hardcode the webjar version: discovering it by glob means bumping the
 * pin needs no edit here, and keeps the assertion about DOMPurify — the thing the advisory is about
 * — rather than about a number that has to be kept in sync in two places.
 */
class SwaggerUiWebjarTest : BaseControllerTest() {
    /**
     * The `swagger-ui-bundle.js` on the runtime classpath, found without naming its version.
     *
     * `classpath*:` with a wildcard segment is the one lookup that reaches inside a jar; a plain
     * `getResource` needs the exact versioned path, and directory entries in jars cannot be listed.
     */
    private fun bundle(): String {
        val found =
            PathMatchingResourcePatternResolver()
                .getResources("classpath*:/META-INF/resources/webjars/swagger-ui/*/swagger-ui-bundle.js")
        found.size shouldBe 1
        return found
            .single()
            .inputStream
            .bufferedReader()
            .readText()
    }

    /**
     * The assertion the advisory is about.
     *
     * `DOMPurify.version="3.4.13"` is a literal DOMPurify's own build writes into the minified
     * bundle, so this is a measurement of the shipped artifact rather than a changelog claim.
     * Compared as a version rather than for equality, so a later webjar carrying 3.4.14 passes with
     * no edit, and a regression to 3.4.12 fails however it arrives.
     */
    @Test
    fun `the bundled DOMPurify is at or past the version that fixes GHSA-55q2-fjhq-7xh7`() {
        val version =
            Regex("""DOMPurify\.version\s*=\s*["']([0-9]+\.[0-9]+\.[0-9]+)["']""")
                .find(bundle())
                ?.groupValues
                ?.get(1)

        version shouldNotBe null
        atLeast(version!!, FIXED_DOM_PURIFY) shouldBe true
    }

    /**
     * The pin is only safe if the docs still render, and springdoc resolves the webjar's *versioned*
     * resource path — so moving that version is precisely the change that could break it. This is
     * why the test boots a context rather than staying a classpath unit test.
     */
    @Test
    fun `Swagger UI still serves after the webjar is pinned ahead of springdoc`() {
        webTestClient
            .get()
            .uri("/webjars/swagger-ui/index.html")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .consumeWith { it.responseBody?.decodeToString()?.shouldContain("swagger-ui") }
    }

    @Test
    fun `the OpenAPI document the UI reads is still generated`() {
        webTestClient
            .get()
            .uri("/v3/api-docs")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.openapi")
            .exists()
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
