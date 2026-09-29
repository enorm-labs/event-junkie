package de.norm.events.scraper

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistDiscogsRepository
import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistRepository
import de.norm.events.artist.DiscogsMatch
import de.norm.events.artist.MusicBrainzMatch
import de.norm.events.discogs.DiscogsCandidate
import de.norm.events.discogs.DiscogsClient
import de.norm.events.discogs.DiscogsProperties
import de.norm.events.discogs.DiscogsUnavailableException
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Year
import java.time.ZoneOffset

/**
 * The Discogs sweep against a real PostgreSQL (Testcontainers), with Discogs replaced by a scripted
 * client. The columns of V061 are the point: only a MusicBrainz `NONE` row is asked, a present link
 * is kept, the CHECK constraints hold, and a verdict does not queue the row again.
 */
class DiscogsLookupServiceIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var artistRepository: ArtistRepository

    @Autowired
    private lateinit var discogsRepository: ArtistDiscogsRepository

    private val client = mockk<DiscogsClient>()
    private val metricsRegistry = SimpleMeterRegistry()
    private val metrics = ImporterMetrics(metricsRegistry)

    // Every EXACT candidate released this year unless a test says otherwise, so rule 5 (#2054) keeps it.
    @BeforeEach
    fun recentByDefault() {
        coEvery { client.newestReleaseYear(any()) } returns Year.now(ZoneOffset.UTC).value
    }

    private fun service(
        maxPerRun: Int = 100,
        key: String = "key"
    ) = DiscogsLookupService(
        artistRepository = discogsRepository,
        client = client,
        properties = DiscogsProperties(consumerKey = key, consumerSecret = "secret", maxPerRun = maxPerRun),
        metrics = metrics
    )

    private suspend fun artist(
        name: String,
        musicbrainz: MusicBrainzMatch = MusicBrainzMatch.NONE,
        discogsUrl: String? = null
    ): ArtistEntity {
        val saved = artistRepository.save(ArtistEntity(name = name, slug = name.lowercase().replace(' ', '-'), discogsUrl = discogsUrl))
        val id = requireNotNull(saved.id)
        artistRepository.storeMusicBrainzVerdict(id, musicbrainz.name, if (musicbrainz == MusicBrainzMatch.EXACT) "mbid-$id" else null)
        return requireNotNull(artistRepository.findById(id))
    }

    private fun candidate(
        id: Long,
        title: String
    ) = DiscogsCandidate(id = id, type = "artist", title = title, uri = "/artist/$id-${title.replace(' ', '-')}")

    private suspend fun reload(id: Long?) = requireNotNull(artistRepository.findById(requireNotNull(id)))

    private fun lookups(state: String) =
        metricsRegistry
            .find(ImporterMetrics.DISCOGS_LOOKUPS)
            .tag("state", state)
            .counter()
            ?.count() ?: 0.0

    // Block bodies, not `= runBlocking { … }`: an expression body whose last statement returns
    // non-Unit makes JUnit silently skip the test.
    @Test
    fun `stores the verdict, fills the empty link, and touches nothing else`() {
        runBlocking {
            val okkyung = artist("Okkyung Lee")
            val nails = artist("Nails")
            coEvery { client.search("Okkyung Lee") } returns listOf(candidate(130715, "Okkyung Lee"), candidate(3373604, "Maze (21)"))
            coEvery { client.search("Nails") } returns listOf(candidate(1, "Nails"), candidate(2, "Nails (2)"))

            service().sweep(setOfNotNull(okkyung.id, nails.id)).stored shouldBe 2

            val exact = reload(okkyung.id)
            exact.discogsMatch shouldBe DiscogsMatch.EXACT.name
            exact.discogsId shouldBe 130715L
            exact.discogsUrl shouldBe "https://www.discogs.com/artist/130715-Okkyung-Lee"
            exact.discogsCheckedAt.shouldNotBeNull()
            exact.name shouldBe "Okkyung Lee"
            exact.musicbrainzMatch shouldBe MusicBrainzMatch.NONE.name
            val ambiguous = reload(nails.id)
            ambiguous.discogsMatch shouldBe DiscogsMatch.AMBIGUOUS.name
            ambiguous.discogsId.shouldBeNull()
            ambiguous.discogsUrl.shouldBeNull()
            lookups("exact") shouldBe 1.0
            lookups("ambiguous") shouldBe 1.0
        }
    }

    @Test
    fun `an EXACT match whose artist last released long ago is stored AMBIGUOUS, without a link`() {
        runBlocking {
            val beatIt = artist("Beat It!")
            val fresh = artist("Okkyung Lee")
            coEvery { client.search("Beat It!") } returns listOf(candidate(6728639, "Beat It!"))
            coEvery { client.search("Okkyung Lee") } returns listOf(candidate(130715, "Okkyung Lee"))
            coEvery { client.newestReleaseYear(6728639) } returns 2001

            service().sweep(setOfNotNull(beatIt.id, fresh.id)).stored shouldBe 2

            val old = reload(beatIt.id)
            old.discogsMatch shouldBe DiscogsMatch.AMBIGUOUS.name
            old.discogsId.shouldBeNull()
            old.discogsUrl.shouldBeNull()
            reload(fresh.id).discogsMatch shouldBe DiscogsMatch.EXACT.name
            lookups("inactive") shouldBe 1.0
            lookups("exact") shouldBe 1.0
            lookups("ambiguous") shouldBe 0.0
        }
    }

    @Test
    fun `reads releases only for an EXACT match`() {
        runBlocking {
            val nails = artist("Nails")
            val morgue = artist("Sonic Morgue")
            coEvery { client.search("Nails") } returns listOf(candidate(1, "Nails"), candidate(2, "Nails (2)"))
            coEvery { client.search("Sonic Morgue") } returns emptyList()

            service().sweep(setOfNotNull(nails.id, morgue.id)).stored shouldBe 2

            coVerify(exactly = 0) { client.newestReleaseYear(any()) }
        }
    }

    @Test
    fun `asks only about rows MusicBrainz marks NONE`() {
        runBlocking {
            val known = artist("Barker", MusicBrainzMatch.EXACT)
            val unsure = artist("Chris Wood", MusicBrainzMatch.AMBIGUOUS)
            val unchecked = artist("Veit", MusicBrainzMatch.UNCHECKED)

            service().sweep(setOfNotNull(known.id, unsure.id, unchecked.id)).stored shouldBe 0

            coVerify(exactly = 0) { client.search(any()) }
            reload(known.id).discogsMatch shouldBe DiscogsMatch.UNCHECKED.name
        }
    }

    @Test
    fun `keeps a link that is already there`() {
        runBlocking {
            val zoh = artist("Zoh Amba", discogsUrl = "https://www.discogs.com/artist/1-Venue-Supplied")
            coEvery { client.search("Zoh Amba") } returns listOf(candidate(7_000_001, "Zoh Amba"))

            service().sweep(setOfNotNull(zoh.id))

            val row = reload(zoh.id)
            row.discogsMatch shouldBe DiscogsMatch.EXACT.name
            row.discogsId shouldBe 7_000_001L
            row.discogsUrl shouldBe "https://www.discogs.com/artist/1-Venue-Supplied"
        }
    }

    @Test
    fun `a row that loses its EXACT verdict loses the link that verdict wrote, and keeps any other`() {
        runBlocking {
            val kevin = artist("Kevin")
            val venueLinked = artist("Victor", discogsUrl = "https://www.discogs.com/artist/1-Venue-Supplied")
            coEvery { client.search("Kevin") } returns listOf(candidate(1007151, "Kevin"))
            coEvery { client.search("Victor") } returns listOf(candidate(642486, "Victor"))
            service().sweep(setOfNotNull(kevin.id, venueLinked.id))
            reload(kevin.id).discogsUrl shouldBe "https://www.discogs.com/artist/1007151-Kevin"

            // Renamed rows are asked again; this time the only hits are suffixed homonyms.
            artistRepository.save(reload(kevin.id).copy(name = "Kevin K"))
            artistRepository.save(reload(venueLinked.id).copy(name = "Victor V"))
            coEvery { client.search("Kevin K") } returns listOf(candidate(1007151, "Kevin K (27)"))
            coEvery { client.search("Victor V") } returns listOf(candidate(642486, "Victor V (10)"))
            service().sweep(setOfNotNull(kevin.id, venueLinked.id)).stored shouldBe 2

            val cleared = reload(kevin.id)
            cleared.discogsMatch shouldBe DiscogsMatch.AMBIGUOUS.name
            cleared.discogsId.shouldBeNull()
            cleared.discogsUrl.shouldBeNull()
            reload(venueLinked.id).discogsUrl shouldBe "https://www.discogs.com/artist/1-Venue-Supplied"
        }
    }

    @Test
    fun `a stored verdict does not queue the row again`() {
        runBlocking {
            val rena = artist("Rena Volvo")
            coEvery { client.search("Rena Volvo") } returns emptyList()

            service().sweep(setOfNotNull(rena.id)).stored shouldBe 1
            service().sweep(setOfNotNull(rena.id)).stored shouldBe 0

            coVerify(exactly = 1) { client.search("Rena Volvo") }
            reload(rena.id).discogsMatch shouldBe DiscogsMatch.NONE.name
        }
    }

    @Test
    fun `a Discogs verdict does not queue the row for MusicBrainz, nor a later MusicBrainz verdict for Discogs`() {
        runBlocking {
            val id = requireNotNull(artist("Zoh Amba").id)
            coEvery { client.search("Zoh Amba") } returns listOf(candidate(4, "Zoh Amba"))

            service().sweep(setOf(id)).stored shouldBe 1
            artistRepository.findNeedingMusicBrainzLookup(setOf(id)).toList().shouldBeEmpty()

            artistRepository.storeMusicBrainzVerdict(id, MusicBrainzMatch.NONE.name, null)
            discogsRepository.findNeedingDiscogsLookup(setOf(id)).toList().shouldBeEmpty()
        }
    }

    @Test
    fun `a renamed row is asked again`() {
        runBlocking {
            val id = requireNotNull(artist("Old Name").id)
            coEvery { client.search(any()) } returns emptyList()
            service().sweep(setOf(id)).stored shouldBe 1

            artistRepository.save(reload(id).copy(name = "New Name"))

            service().sweep(setOf(id)).stored shouldBe 1
            coVerify { client.search("New Name") }
        }
    }

    @Test
    fun `drains the backfill after the touched rows, bounded by maxPerRun`() {
        runBlocking {
            val backlog = (1..3).map { artist("Backlog $it") }
            coEvery { client.search(any()) } returns emptyList()

            service(maxPerRun = 2).sweep(emptySet()).stored shouldBe 2

            reload(backlog[2].id).discogsMatch shouldBe DiscogsMatch.UNCHECKED.name
            discogsRepository.countUncheckedByDiscogs() shouldBe 1L
        }
    }

    @Test
    fun `stops after three unavailable rows in a row and stores nothing for them`() {
        runBlocking {
            val rows = (1..5).map { artist("Outage $it") }
            coEvery { client.search(any()) } throws DiscogsUnavailableException("Discogs answered 503 for 'x'")

            service().sweep(rows.mapNotNull { it.id }.toSet()).stored shouldBe 0

            coVerify(exactly = 3) { client.search(any()) }
            lookups("error") shouldBe 3.0
            rows.forEach { reload(it.id).discogsMatch shouldBe DiscogsMatch.UNCHECKED.name }
        }
    }

    @Test
    fun `reports the unasked NONE rows as its backlog, and zero while it is off`() {
        runBlocking {
            artist("Horst Haller")
            artist("Barker", MusicBrainzMatch.EXACT)

            service().backlog() shouldBe 1L
            service(key = "").backlog() shouldBe 0L
        }
    }

    @Test
    fun `sends nothing without credentials`() {
        runBlocking {
            val row = artist("Angel D'lite")

            service(key = "").sweep(setOfNotNull(row.id)) shouldBe LookupPass.OFF

            coVerify(exactly = 0) { client.search(any()) }
        }
    }
}
