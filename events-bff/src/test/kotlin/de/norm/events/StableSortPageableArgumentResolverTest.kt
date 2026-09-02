package de.norm.events

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.BindingContext

/**
 * Unit tests for [StableSortPageableArgumentResolver].
 *
 * Asserts the tiebreaker is appended for both the `@PageableDefault` path and an explicit
 * client `sort` — the latter being the one the SPA actually uses, and the one a
 * default-only fix would have missed.
 *
 * The importer carries the same resolver and an equivalent test.
 */
class StableSortPageableArgumentResolverTest {
    private val resolver = StableSortPageableArgumentResolver()

    /** Stand-in controller method supplying the `@PageableDefault` metadata the resolver reads. */
    @Suppress("UnusedParameter", "unused")
    private fun handler(
        @PageableDefault(size = 20, sort = ["name"]) pageable: Pageable
    ) = Unit

    private fun resolve(query: String): Pageable {
        val parameter = MethodParameter(javaClass.getDeclaredMethod("handler", Pageable::class.java), 0)
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/venues$query"))
        return resolver.resolveArgumentValue(parameter, BindingContext(), exchange)
    }

    private fun properties(query: String): List<String> = resolve(query).sort.toList().map { it.property }

    @Test
    fun `appends id to the declared default sort`() {
        resolve("").sort shouldBe Sort.by("name").and(Sort.by("id"))
    }

    @Test
    fun `appends id to an explicit client sort`() {
        // The SPA sends `sort`, which replaces @PageableDefault entirely — so this is the
        // path that actually has to be stabilised.
        resolve("?sort=name,asc").sort shouldBe Sort.by(Sort.Direction.ASC, "name").and(Sort.by("id"))
    }

    @Test
    fun `keeps the tiebreaker ascending regardless of the primary direction`() {
        val sort = resolve("?sort=eventDate,desc").sort
        sort.getOrderFor("eventDate")!!.isDescending shouldBe true
        sort.getOrderFor("id")!!.isAscending shouldBe true
    }

    @Test
    fun `appends id after every requested sort key`() {
        properties("?sort=eventDate,desc&sort=title,asc") shouldBe listOf("eventDate", "title", "id")
    }

    @Test
    fun `does not append a second id order when the caller already sorts by id`() {
        properties("?sort=id,desc") shouldBe listOf("id")
        properties("?sort=name,asc&sort=id,desc") shouldBe listOf("name", "id")
    }

    @Test
    fun `preserves the requested page and size`() {
        val pageable = resolve("?page=3&size=50")
        pageable.pageNumber shouldBe 3
        pageable.pageSize shouldBe 50
    }
}
