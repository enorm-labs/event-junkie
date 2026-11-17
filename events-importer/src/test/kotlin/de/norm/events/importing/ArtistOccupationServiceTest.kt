package de.norm.events.importing

import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistOccupationRepository
import de.norm.events.enrichment.LookupPass
import de.norm.events.wikimedia.WikimediaClient
import de.norm.events.wikimedia.WikimediaProperties
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class ArtistOccupationServiceTest {
    private val occupations: ArtistOccupationRepository = mockk(relaxed = true)
    private val wikimedia: WikimediaClient = mockk()
    private val service = ArtistOccupationService(occupations, wikimedia, WikimediaProperties())

    private fun artist(id: Long) = ArtistEntity(id = id, name = "Act $id", slug = "act-$id", wikidataUrl = "https://www.wikidata.org/wiki/Q$id")

    @Test
    fun `the pass ends at its budget, and the rows after it wait for the next tick (#2896)`() =
        runTest {
            every { occupations.findOccupationReads(any(), any()) } returns flowOf(artist(1), artist(2), artist(3), artist(4))
            val clock = TestTimeSource()
            coEvery { wikimedia.occupationsOf(any()) } coAnswers {
                clock += 10.seconds
                emptySet()
            }

            service.sweep(clock.markNow() + 15.seconds) shouldBe LookupPass(owed = 4, stored = 2)

            coVerify(exactly = 2) { occupations.storeComedian(any(), false) }
        }

    @Test
    fun `asks for the rows read more than the reread interval ago, and stores a changed answer over the old one (#5253)`() =
        runTest {
            val now = Instant.parse("2026-11-17T12:00:00Z")
            val properties = WikimediaProperties(rereadAfter = Duration.ofDays(90))
            val stale = artist(5)
            every { occupations.findOccupationReads(any(), any()) } returns flowOf(stale)
            coEvery { wikimedia.occupationsOf("Q5") } returns setOf("Q245068")

            ArtistOccupationService(occupations, wikimedia, properties, Clock.fixed(now, ZoneOffset.UTC)).sweep() shouldBe
                LookupPass(owed = 1, stored = 1)

            coVerify { occupations.findOccupationReads(100, now.minus(Duration.ofDays(90))) }
            coVerify { occupations.storeComedian(5, true) }
        }
}
