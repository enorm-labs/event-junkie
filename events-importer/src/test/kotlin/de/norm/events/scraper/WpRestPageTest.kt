package de.norm.events.scraper

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Unit tests for [walkWpRestPages], on synthetic pages: each body is its post ids, comma-separated. */
class WpRestPageTest {
    private val apiClient: ApiClient = mockk()
    private val baseUrl = "https://venue.example/wp-json/wp/v2/event"

    private fun pageUrl(page: Int) = "$baseUrl?per_page=3&page=$page"

    private data class Page(
        override val items: List<String>,
        override val postCount: Int
    ) : WpRestPage<String>

    /** A post id starting with `x` fails to parse: it counts as a post but yields no item. */
    private fun read(json: String): Page {
        val posts = json.split(',').filter { it.isNotBlank() }
        return Page(posts.filterNot { it.startsWith('x') }, posts.size)
    }

    private suspend fun walk(lastPage: (Page) -> Boolean = { false }) =
        apiClient.walkWpRestPages(EventSource.HEIMATHAFEN, ::pageUrl, perPage = 3, maxPages = 4, read = ::read, lastPage = lastPage)

    @Test
    fun `reads pages until a short one, which is the last`() =
        runTest {
            coEvery { apiClient.fetchJson(pageUrl(1)) } returns "a,b,c"
            coEvery { apiClient.fetchJson(pageUrl(2)) } returns "d"

            val walked = walk()

            walked.items shouldContainExactly listOf("a", "b", "c", "d")
            walked.complete shouldBe true
            coVerify(exactly = 0) { apiClient.fetchJson(pageUrl(3)) }
        }

    @Test
    fun `counts posts that fail to parse, so they do not end the walk early`() =
        runTest {
            coEvery { apiClient.fetchJson(pageUrl(1)) } returns "a,x1,c"
            coEvery { apiClient.fetchJson(pageUrl(2)) } returns ""

            walk().items shouldContainExactly listOf("a", "c")
            coVerify(exactly = 1) { apiClient.fetchJson(pageUrl(2)) }
        }

    @Test
    fun `stops early when the venue's own rule says the rest is past`() =
        runTest {
            coEvery { apiClient.fetchJson(pageUrl(1)) } returns "a,b,c"

            walk(lastPage = { true }).complete shouldBe true
            coVerify(exactly = 0) { apiClient.fetchJson(pageUrl(2)) }
        }

    @Test
    fun `reports a walk cut at its page cap as incomplete`() =
        runTest {
            coEvery { apiClient.fetchJson(any()) } returns "a,b,c"

            walk().complete shouldBe false
            coVerify(exactly = 4) { apiClient.fetchJson(any()) }
        }

    @Test
    fun `keeps the pages read when a later page fails, and reports the walk incomplete`() =
        runTest {
            coEvery { apiClient.fetchJson(pageUrl(1)) } returns "a,b,c"
            coEvery { apiClient.fetchJson(pageUrl(2)) } throws HttpFetchException(503, pageUrl(2))

            val walked = walk()

            walked.items shouldContainExactly listOf("a", "b", "c")
            walked.complete shouldBe false
        }
}
