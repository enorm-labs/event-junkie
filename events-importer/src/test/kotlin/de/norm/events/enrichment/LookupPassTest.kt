package de.norm.events.enrichment

import de.norm.events.artist.ArtistEntity
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class LookupPassTest {
    private val rows = (1L..6L).map { ArtistEntity(id = it, name = "Act $it", slug = "act-$it") }

    private suspend fun drain(
        outcomes: Map<Long, LookupPass.Row>,
        read: MutableList<Long>
    ) = LookupPass.drain("Test pass", rows, LookupPass.noDeadline()) { artist ->
        read += checkNotNull(artist.id)
        outcomes[artist.id] ?: LookupPass.Row.Stored
    }

    @Test
    fun `three failed rows in a row end the pass, and the rest wait`() =
        runTest {
            val read = mutableListOf<Long>()
            val failed = LookupPass.Row.Failed(IllegalStateException("down"))

            drain(mapOf(2L to failed, 3L to failed, 4L to failed), read) shouldBe LookupPass(owed = 6, stored = 1)

            read shouldContainExactly listOf(1L, 2L, 3L, 4L)
        }

    @Test
    fun `a stored row resets the failure count`() =
        runTest {
            val read = mutableListOf<Long>()
            val failed = LookupPass.Row.Failed(IllegalStateException("down"))

            drain(mapOf(1L to failed, 2L to failed, 4L to failed, 5L to failed), read) shouldBe LookupPass(owed = 6, stored = 2)

            read shouldContainExactly (1L..6L).toList()
        }

    @Test
    fun `a stop ends the pass at once, and the stopped row is not counted`() =
        runTest {
            val read = mutableListOf<Long>()

            drain(mapOf(3L to LookupPass.Row.Stop), read) shouldBe LookupPass(owed = 6, stored = 2)

            read shouldContainExactly listOf(1L, 2L, 3L)
        }

    @Test
    fun `a spent deadline reads nothing (#2896)`() =
        runTest {
            val read = mutableListOf<Long>()
            val clock = TestTimeSource()
            val deadline = clock.markNow() + 1.seconds
            clock += 2.seconds

            LookupPass.drain("Test pass", rows, deadline) { artist ->
                read += checkNotNull(artist.id)
                LookupPass.Row.Stored
            } shouldBe LookupPass(owed = 6, stored = 0)

            read.shouldBeEmpty()
        }

    @Test
    fun `an empty slice owes and stores nothing`() =
        runTest {
            LookupPass.drain("Test pass", emptyList(), LookupPass.noDeadline()) { LookupPass.Row.Stored } shouldBe LookupPass(owed = 0, stored = 0)
        }
}
