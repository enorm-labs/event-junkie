package de.norm.events.enrichment

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistRepository
import de.norm.events.artist.MusicBrainzMatch
import de.norm.events.musicbrainz.MusicBrainzCandidate
import de.norm.events.musicbrainz.MusicBrainzClient
import de.norm.events.musicbrainz.MusicBrainzProperties
import de.norm.events.musicbrainz.MusicBrainzUnavailableException
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The sweep against a real PostgreSQL (Testcontainers), with MusicBrainz replaced by a scripted
 * client. What the columns of V037 do under the verdict is the point — the CHECK constraints, the
 * partial-index query, and that a verdict leaves `name` alone and does not queue the row again
 * — and a repository double would assert none of it.
 */
class MusicBrainzLookupServiceIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var artistRepository: ArtistRepository

    private val client = mockk<MusicBrainzClient>()
    private val metricsRegistry = SimpleMeterRegistry()
    private val metrics = EnrichmentMetrics(metricsRegistry)

    private fun service(maxPerRun: Int = 500) =
        MusicBrainzLookupService(
            artistRepository = artistRepository,
            client = client,
            properties = MusicBrainzProperties(maxPerRun = maxPerRun),
            metrics = metrics
        )

    private suspend fun artist(name: String) = artistRepository.save(ArtistEntity(name = name, slug = name.lowercase().replace(' ', '-')))

    private fun candidate(
        name: String,
        id: String,
        country: String? = null
    ) = MusicBrainzCandidate(id = id, name = name, country = country)

    private suspend fun matchOf(id: Long) = requireNotNull(artistRepository.findById(id)).musicbrainzMatch

    private fun lookups(state: String) =
        metricsRegistry
            .find(EnrichmentMetrics.MUSICBRAINZ_LOOKUPS)
            .tag("state", state)
            .counter()
            ?.count() ?: 0.0

    // Block bodies, not `= runBlocking { … }`: an expression body whose last statement returns
    // non-Unit makes JUnit silently skip the test.
    @Test
    fun `stores the verdict and touches nothing else on the row`() {
        runBlocking {
            val accept = artist("Accept")
            val pici = artist("Pici")
            coEvery { client.search("Accept") } returns listOf(candidate("Accept", "mbid-de", "DE"), candidate("ACCEPT", "mbid-jp", "JP"))
            coEvery { client.search("Pici") } returns listOf(candidate("Pici Mazzei", "mbid-it", "IT"))

            service().sweep(setOfNotNull(accept.id, pici.id)).stored shouldBe 2

            val acceptId = requireNotNull(accept.id)
            val storedAccept = artistRepository.findById(acceptId).shouldNotBeNull()
            storedAccept.musicbrainzMatch shouldBe MusicBrainzMatch.EXACT.name
            storedAccept.musicbrainzId shouldBe "mbid-de"
            storedAccept.name shouldBe "Accept"
            artistRepository.findNeedingMusicBrainzLookup(setOf(acceptId)).toList() shouldBe emptyList()

            val storedPici = artistRepository.findById(requireNotNull(pici.id)).shouldNotBeNull()
            storedPici.musicbrainzMatch shouldBe MusicBrainzMatch.NONE.name
            storedPici.musicbrainzId.shouldBeNull()
            lookups("exact") shouldBe 1.0
            lookups("none") shouldBe 1.0
        }
    }

    @Test
    fun `a row with a current verdict is not looked up again, and the backfill fills the slice`() {
        runBlocking {
            val checked = artist("Checked")
            val checkedId = requireNotNull(checked.id)
            artistRepository.storeMusicBrainzVerdict(checkedId, MusicBrainzMatch.AMBIGUOUS.name, null)
            val touched = artist("Touched")
            val backlogA = artist("Backlog A")
            val backlogB = artist("Backlog B")
            coEvery { client.search(any()) } returns emptyList()

            // Room for two: the touched row first, then the oldest unchecked row — not the second.
            service(maxPerRun = 2).sweep(setOfNotNull(checked.id, touched.id)).stored shouldBe 2

            matchOf(checkedId) shouldBe MusicBrainzMatch.AMBIGUOUS.name
            matchOf(requireNotNull(touched.id)) shouldBe MusicBrainzMatch.NONE.name
            matchOf(requireNotNull(backlogA.id)) shouldBe MusicBrainzMatch.NONE.name
            matchOf(requireNotNull(backlogB.id)) shouldBe MusicBrainzMatch.UNCHECKED.name
            artistRepository.countUncheckedByMusicBrainz() shouldBe 1
        }
    }

    @Test
    fun `a renamed row is looked up again, because its name changed after the verdict`() {
        runBlocking {
            val id = requireNotNull(artist("Old Name").id)
            artistRepository.storeMusicBrainzVerdict(id, MusicBrainzMatch.EXACT.name, "mbid-old")
            artistRepository.save(requireNotNull(artistRepository.findById(id)).copy(name = "New Name"))
            coEvery { client.search("New Name") } returns emptyList()

            service().sweep(setOf(id)).stored shouldBe 1

            val stored = requireNotNull(artistRepository.findById(id))
            stored.musicbrainzMatch shouldBe MusicBrainzMatch.NONE.name
            stored.musicbrainzId.shouldBeNull()
        }
    }

    @Test
    fun `a write that leaves the name alone does not queue the row again`() {
        runBlocking {
            val id = requireNotNull(artist("Kept Name").id)
            artistRepository.storeMusicBrainzVerdict(id, MusicBrainzMatch.NONE.name, null)
            artistRepository.save(requireNotNull(artistRepository.findById(id)).copy(websiteUrl = "https://kept.example"))

            service().sweep(setOf(id)).stored shouldBe 0
        }
    }

    @Test
    fun `a row MusicBrainz will not answer for is counted and skipped, and the rows after it are stored`() {
        runBlocking {
            val first = artist("First")
            val second = artist("Second")
            val third = artist("Third")
            coEvery { client.search("First") } throws MusicBrainzUnavailableException("503 four times")
            coEvery { client.search("Second") } returns emptyList()
            coEvery { client.search("Third") } returns emptyList()

            service().sweep(setOfNotNull(first.id, second.id, third.id)).stored shouldBe 2

            matchOf(requireNotNull(first.id)) shouldBe MusicBrainzMatch.UNCHECKED.name
            matchOf(requireNotNull(second.id)) shouldBe MusicBrainzMatch.NONE.name
            matchOf(requireNotNull(third.id)) shouldBe MusicBrainzMatch.NONE.name
            lookups("error") shouldBe 1.0
        }
    }

    @Test
    fun `three unanswered rows in a row end the run, and the rest wait for the next one`() {
        runBlocking {
            val ids = listOf("A", "B", "C", "D").map { requireNotNull(artist(it).id) }
            coEvery { client.search(any()) } throws MusicBrainzUnavailableException("503 four times")

            service().sweep(ids.toSet()).stored shouldBe 0

            ids.forEach { matchOf(it) shouldBe MusicBrainzMatch.UNCHECKED.name }
            lookups("error") shouldBe 3.0
            coVerify(exactly = 3) { client.search(any()) }
        }
    }

    @Test
    fun `a burst during the head pass costs the head, never the verdict`() {
        runBlocking {
            val id = requireNotNull(artist("Current 93 - Sonic Morgue").id)
            coEvery { client.search("Current 93 - Sonic Morgue") } returns emptyList()
            coEvery { client.search("Current 93") } throws MusicBrainzUnavailableException("503 four times")

            service().sweep(setOf(id)).stored shouldBe 1

            matchOf(id) shouldBe MusicBrainzMatch.NONE.name
            lookups("error") shouldBe 0.0
        }
    }

    @Test
    fun `disabled means no lookup at all`() {
        runBlocking {
            val id = requireNotNull(artist("Anyone").id)
            val disabled = MusicBrainzLookupService(artistRepository, client, MusicBrainzProperties(enabled = false), metrics)

            disabled.sweep(setOf(id)) shouldBe LookupPass.OFF

            matchOf(id) shouldBe MusicBrainzMatch.UNCHECKED.name
        }
    }
}
