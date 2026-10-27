package de.norm.events.account

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The security chain and the two filters outside it must agree on every route, or a path is authenticated but cacheable,
 * or public but `no-store` (#4038). Every mapped route is read from the controllers, so a new one is checked without
 * an edit here.
 */
class AccountPathTest {
    private val chain = accountPathMatcher()

    @Test
    fun `the chain's patterns and isAccountPath agree on every mapped route`() {
        val routes = mappedRoutes()

        routes.forEach { path ->
            withClue(path) { isAccountPath(path) shouldBe chainMatches(path) }
        }
        routes.filter(::isAccountPath).map { it.substringAfter("/api/").substringBefore('/') }.toSet() shouldBe setOf("auth", "me")
    }

    @Test
    fun `every account controller's root is an account path`() {
        mappedRoutes().filter(::isAccountPath).shouldContainAll(
            "/api/me",
            "/api/me/searches",
            "/api/me/rsvps",
            "/api/me/follows",
            "/api/me/push",
            "/api/auth/session"
        )
    }

    @Test
    fun `a route that only starts the same is not an account path`() {
        listOf("/api/meta", "/api/mexico", "/api/authors", "/api/events").forEach { path ->
            withClue(path) {
                isAccountPath(path) shouldBe false
                chainMatches(path) shouldBe false
            }
        }
    }

    private fun chainMatches(path: String) =
        chain
            .matches(MockServerWebExchange.from(MockServerHttpRequest.get(path)))
            .block()!!
            .isMatch

    private fun mappedRoutes(): List<String> {
        val scanner = ClassPathScanningCandidateComponentProvider(false).apply { addIncludeFilter(AnnotationTypeFilter(RestController::class.java)) }
        return scanner
            .findCandidateComponents("de.norm.events")
            .map { Class.forName(it.beanClassName) }
            .flatMap { controller ->
                val roots =
                    AnnotatedElementUtils
                        .findMergedAnnotation(controller, RequestMapping::class.java)
                        ?.path
                        ?.toList()
                        .orEmpty()
                        .ifEmpty { listOf("") }
                controller.declaredMethods
                    .mapNotNull { AnnotatedElementUtils.findMergedAnnotation(it, RequestMapping::class.java) }
                    .flatMap { mapping -> mapping.path.toList().ifEmpty { listOf("") } }
                    .flatMap { sub -> roots.map { root -> (root + sub).replace(PATH_VARIABLE, "x") } }
            }.distinct()
    }

    private companion object {
        val PATH_VARIABLE = Regex("""\{[^}]+}""")
    }
}
