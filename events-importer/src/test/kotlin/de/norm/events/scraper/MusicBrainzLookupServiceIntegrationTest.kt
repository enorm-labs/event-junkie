package de.norm.events.scraper

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
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The sweep against a real PostgreSQL (Testcontainers), with MusicBrainz replaced by a scripted
 * client. What the columns of V037 do under the verdict is the point — the CHECK constraints, the
 * partial-index query, and that a verdict leaves `name` alone and does not queue the row again
 * through `trg_artist_updated_at` — and a repository double would assert none of it.
 */
class MusicBrainzLookupServiceIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var artistRepository: ArtistRepository

    private val client = mockk<MusicBrainzClient>()
    private val metricsRegistry = SimpleMeterRegistry()
    private val metrics = ImporterMetrics(metricsRegistry)

    private fun service(maxPerRun: Int = 500) =
        MusicBrainzLookupService(
            artistRepository = artistRepository,
            client = client,
            properties = MusicBrainzProperties(maxPerRun = maxPerRun),
            metrics = metrics
        )

    private fun source() =
        EventSourceEntity(id = 1L, venueId = 1L, name = "Cassiopeia", slug = "cassiopeia", url = "https://cassiopeia.example/events", sourceType = "CASSIOPEIA")

    private suspend fun artist(name: String) = artistRepository.save(ArtistEntity(name = name, slug = name.lowercase().replace(' ', '-')))

    private fun candidate(
        name: String,
        id: String,
        country: String? = null
    ) = MusicBrainzCandidate(id = id, name = name, country = country)

    private suspend fun matchOf(id: Long) = requireNotNull(artistRepository.findById(id)).musicbrainzMatch

    private fun lookups(state: String) =
        metricsRegistry
            .find(ImporterMetrics.MUSICBRAINZ_LOOKUPS)
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

            service().lookupFor(source(), setOfNotNull(accept.id, pici.id)) shouldBe 2

            val acceptId = requireNotNull(accept.id)
            val storedAccept = artistRepository.findById(acceptId).shouldNotBeNull()
            storedAccept.musicbrainzMatch shouldBe MusicBrainzMatch.EXACT.name
            storedAccept.musicbrainzId shouldBe "mbid-de"
            storedAccept.name shouldBe "Accept"
            // The trigger bumped `updated_at` to the same `now()` the verdict wrote, so the row is
            // current, not "renamed since checked".
            storedAccept.musicbrainzCheckedAt shouldBe storedAccept.updatedAt
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
            service(maxPerRun = 2).lookupFor(source(), setOfNotNull(checked.id, touched.id)) shouldBe 2

            matchOf(checkedId) shouldBe MusicBrainzMatch.AMBIGUOUS.name
            matchOf(requireNotNull(touched.id)) shouldBe MusicBrainzMatch.NONE.name
            matchOf(requireNotNull(backlogA.id)) shouldBe MusicBrainzMatch.NONE.name
            matchOf(requireNotNull(backlogB.id)) shouldBe MusicBrainzMatch.UNCHECKED.name
            artistRepository.countUncheckedByMusicBrainz() shouldBe 1
        }
    }

    @Test
    fun `a renamed row is looked up again, because its updated_at outran the verdict`() {
        runBlocking {
            val id = requireNotNull(artist("Old Name").id)
            artistRepository.storeMusicBrainzVerdict(id, MusicBrainzMatch.EXACT.name, "mbid-old")
            artistRepository.save(requireNotNull(artistRepository.findById(id)).copy(name = "New Name"))
            coEvery { client.search("New Name") } returns emptyList()

            service().lookupFor(source(), setOf(id)) shouldBe 1

            val stored = requireNotNull(artistRepository.findById(id))
            stored.musicbrainzMatch shouldBe MusicBrainzMatch.NONE.name
            stored.musicbrainzId.shouldBeNull()
        }
    }

    @Test
    fun `MusicBrainz being unavailable stores nothing, throws nothing, and counts once`() {
        runBlocking {
            val first = artist("First")
            val second = artist("Second")
            coEvery { client.search(any()) } throws MusicBrainzUnavailableException("503 twice")

            service().lookupFor(source(), setOfNotNull(first.id, second.id)) shouldBe 0

            matchOf(requireNotNull(first.id)) shouldBe MusicBrainzMatch.UNCHECKED.name
            matchOf(requireNotNull(second.id)) shouldBe MusicBrainzMatch.UNCHECKED.name
            lookups("error") shouldBe 1.0
        }
    }

    @Test
    fun `disabled means no lookup at all`() {
        runBlocking {
            val id = requireNotNull(artist("Anyone").id)
            val disabled = MusicBrainzLookupService(artistRepository, client, MusicBrainzProperties(enabled = false), metrics)

            disabled.lookupFor(source(), setOf(id)) shouldBe 0

            matchOf(id) shouldBe MusicBrainzMatch.UNCHECKED.name
        }
    }
}
