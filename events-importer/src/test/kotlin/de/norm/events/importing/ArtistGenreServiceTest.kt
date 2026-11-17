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
            every { genres.findUnreadGenres(100) } returns flowOf(artist(69263), artist(2))
            coEvery { wikimedia.genresOf("Q69263") } returns listOf("Q959583", "Q9759")
            coEvery { wikimedia.genresOf("Q2") } returns listOf("Q1062702")

            service().sweep() shouldBe LookupPass(owed = 2, stored = 2)

            coVerify { genres.storeGenres(69263, arrayOf("Q959583", "Q9759")) }
            coVerify { genres.storeGenres(2, arrayOf("Q1062702")) }
        }

    @Test
    fun `a link with no item id is stored as no genre, so the row leaves the queue`() =
        runTest {
            every { genres.findUnreadGenres(any()) } returns flowOf(artist(3, wikidataUrl = "https://www.wikidata.org/wiki/Special:Search"))

            service().sweep() shouldBe LookupPass(owed = 1, stored = 1)

            coVerify { genres.storeGenres(3, emptyArray()) }
            coVerify(exactly = 0) { wikimedia.genresOf(any()) }
        }

    @Test
    fun `a failed read stores nothing, and the row waits for the next tick`() =
        runTest {
            every { genres.findUnreadGenres(any()) } returns flowOf(artist(4))
            coEvery { wikimedia.genresOf(any()) } throws WikimediaUnavailableException("Wikidata Q4 answered 503")

            service().sweep().stored shouldBe 0

            coVerify(exactly = 0) { genres.storeGenres(any(), any()) }
        }

    @Test
    fun `a switched-off read is off and asks for no row`() =
        runTest {
            service(WikimediaProperties(enabled = false)).sweep() shouldBe LookupPass.OFF

            coVerify(exactly = 0) { genres.findUnreadGenres(any()) }
        }
}
