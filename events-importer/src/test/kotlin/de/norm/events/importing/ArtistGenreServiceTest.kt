package de.norm.events.importing

import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistGenreRepository
import de.norm.events.enrichment.LookupPass
import de.norm.events.wikimedia.WikimediaClient
import de.norm.events.wikimedia.WikimediaProperties
import de.norm.events.wikimedia.WikimediaUnavailableException
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

class ArtistGenreServiceTest {
    private val genres: ArtistGenreRepository = mockk(relaxed = true)
    private val wikimedia: WikimediaClient = mockk()

    private fun service(properties: WikimediaProperties = WikimediaProperties()) = ArtistGenreService(genres, wikimedia, properties)

    private fun artist(
        id: Long,
        wikidataUrl: String = "https://www.wikidata.org/wiki/Q$id"
    ) = ArtistEntity(id = id, name = "Act $id", slug = "act-$id", wikidataUrl = wikidataUrl)

    @Test
    fun `stores the item ids in statement order, a mapped one and an unmapped one alike`() =
        runTest {
            every { genres.findGenreReads(100, any()) } returns flowOf(artist(69263), artist(2))
            coEvery { wikimedia.genresOf("Q69263") } returns listOf("Q959583", "Q9759")
            coEvery { wikimedia.genresOf("Q2") } returns listOf("Q1062702")

            service().sweep() shouldBe LookupPass(owed = 2, stored = 2)

            coVerify { genres.storeGenres(69263, arrayOf("Q959583", "Q9759")) }
            coVerify { genres.storeGenres(2, arrayOf("Q1062702")) }
        }

    @Test
    fun `a link with no item id is stored as no genre, so the row leaves the queue`() =
        runTest {
            every { genres.findGenreReads(any(), any()) } returns flowOf(artist(3, wikidataUrl = "https://www.wikidata.org/wiki/Special:Search"))

            service().sweep() shouldBe LookupPass(owed = 1, stored = 1)

            coVerify { genres.storeGenres(3, emptyArray()) }
            coVerify(exactly = 0) { wikimedia.genresOf(any()) }
        }

    @Test
    fun `a failed read stores nothing, and the row waits for the next tick`() =
        runTest {
            every { genres.findGenreReads(any(), any()) } returns flowOf(artist(4))
            coEvery { wikimedia.genresOf(any()) } throws WikimediaUnavailableException("Wikidata Q4 answered 503")

            service().sweep().stored shouldBe 0

            coVerify(exactly = 0) { genres.storeGenres(any(), any()) }
        }

    @Test
    fun `asks for the rows read more than the reread interval ago, and stores a changed answer over the old one (#5253)`() =
        runTest {
            val now = Instant.parse("2026-11-17T12:00:00Z")
            val properties = WikimediaProperties(rereadAfter = Duration.ofDays(90))
            every { genres.findGenreReads(any(), any()) } returns flowOf(artist(5))
            coEvery { wikimedia.genresOf("Q5") } returns listOf("Q8341")

            ArtistGenreService(genres, wikimedia, properties, Clock.fixed(now, ZoneOffset.UTC)).sweep() shouldBe LookupPass(owed = 1, stored = 1)

            coVerify { genres.findGenreReads(100, now.minus(Duration.ofDays(90))) }
            coVerify { genres.storeGenres(5, arrayOf("Q8341")) }
        }

    @Test
    fun `a switched-off read is off and asks for no row`() =
        runTest {
            service(WikimediaProperties(enabled = false)).sweep() shouldBe LookupPass.OFF

            coVerify(exactly = 0) { genres.findGenreReads(any(), any()) }
        }
}
