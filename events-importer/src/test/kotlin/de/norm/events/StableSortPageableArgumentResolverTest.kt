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
 */
class StableSortPageableArgumentResolverTest {
    private val resolver = StableSortPageableArgumentResolver(MAX_PAGE_SIZE)

    /** Stand-in controller method supplying the `@PageableDefault` metadata the resolver reads. */
    @Suppress("UnusedParameter", "unused")
    private fun handler(
        @PageableDefault(size = 20, sort = ["name"]) pageable: Pageable
    ) = Unit

    private fun resolve(query: String): Pageable {
        val parameter = MethodParameter(javaClass.getDeclaredMethod("handler", Pageable::class.java), 0)
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/admin/artists$query"))
        return resolver.resolveArgumentValue(parameter, BindingContext(), exchange)
    }

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
        resolve("?sort=eventDate,desc&sort=title,asc").sort.toList().map { it.property } shouldBe
            listOf("eventDate", "title", "id")
    }

    @Test
    fun `does not append a second id order when the caller already sorts by id`() {
        resolve("?sort=id,desc").sort.toList().map { it.property } shouldBe listOf("id")
        resolve("?sort=name,asc&sort=id,desc").sort.toList().map { it.property } shouldBe listOf("name", "id")
    }

    @Test
    fun `preserves the requested page and size`() {
        val pageable = resolve("?page=3&size=50")
        pageable.pageNumber shouldBe 3
        pageable.pageSize shouldBe 50
    }

    @Test
    fun `clamps a page size above the cap instead of rejecting it`() {
        // Spring Data's own default is 2000, and it applies whenever nothing sets a cap. The request
        // still succeeds, and the listing's `totalElements` is what says it was clamped (#810).
        resolve("?size=5000").pageSize shouldBe MAX_PAGE_SIZE
        resolve("?size=2000").pageSize shouldBe MAX_PAGE_SIZE
    }

    @Test
    fun `leaves the page number alone when the size is clamped`() {
        val pageable = resolve("?page=2&size=5000")
        pageable.pageNumber shouldBe 2
        pageable.pageSize shouldBe MAX_PAGE_SIZE
    }

    private companion object {
        /** The cap under test. `MaxPageSizeConfigTest` is what checks the shipped file sets one. */
        const val MAX_PAGE_SIZE = 100
    }
}
