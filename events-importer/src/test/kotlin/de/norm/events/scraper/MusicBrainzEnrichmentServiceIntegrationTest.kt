package de.norm.events.scraper

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistEnrichmentStore
import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistRepository
import de.norm.events.artist.MusicBrainzMatch
import de.norm.events.musicbrainz.MusicBrainzArea
import de.norm.events.musicbrainz.MusicBrainzArtist
import de.norm.events.musicbrainz.MusicBrainzClient
import de.norm.events.musicbrainz.MusicBrainzLifeSpan
import de.norm.events.musicbrainz.MusicBrainzProperties
import de.norm.events.musicbrainz.MusicBrainzUnavailableException
import de.norm.events.musicbrainz.MusicBrainzUrl
import de.norm.events.musicbrainz.MusicBrainzUrlRelation
import de.norm.events.wikimedia.CommonsImage
import de.norm.events.wikimedia.WikimediaClient
import de.norm.events.wikimedia.WikipediaExtract
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException

/**
 * Step C against a real PostgreSQL, with MusicBrainz and Wikimedia replaced by scripted clients.
 * What V040's columns and CHECKs do under a fill, and that the write does not queue the row for
 * another lookup through `trg_artist_updated_at`, are the point; a repository double asserts none of it.
 */
class MusicBrainzEnrichmentServiceIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var artistRepository: ArtistRepository

    @Autowired
    private lateinit var store: ArtistEnrichmentStore

    private val musicBrainz = mockk<MusicBrainzClient>()
    private val wikimedia =
        mockk<WikimediaClient> {
            every { maxBytes } returns 8L * 1024 * 1024
            coEvery { extractFor(any(), any()) } returns null
        }
    private val registry = SimpleMeterRegistry()
    private val metrics = ImporterMetrics(registry)

    private fun service(maxPerRun: Int = 100) =
        MusicBrainzEnrichmentService(
            artistRepository = artistRepository,
            store = store,
            musicBrainz = musicBrainz,
            wikimedia = wikimedia,
            properties = MusicBrainzProperties(enrichMaxPerRun = maxPerRun),
            metrics = metrics
        )

    private fun source() =
        EventSourceEntity(id = 1L, venueId = 1L, name = "Cassiopeia", slug = "cassiopeia", url = "https://cassiopeia.example/events", sourceType = "CASSIOPEIA")

    /** An EXACT row, the way the lookup leaves one. */
    private suspend fun exact(
        name: String,
        mbid: String = "mbid-${name.lowercase()}",
        websiteUrl: String? = null
    ): Long {
        val saved = artistRepository.save(ArtistEntity(name = name, slug = name.lowercase().replace(' ', '-'), websiteUrl = websiteUrl))
        val id = requireNotNull(saved.id)
        artistRepository.storeMusicBrainzVerdict(id, MusicBrainzMatch.EXACT.name, mbid)
        return id
    }

    private fun relation(
        type: String,
        url: String,
        ended: Boolean = false
    ) = MusicBrainzUrlRelation(type = type, ended = ended, url = MusicBrainzUrl(url))

    private fun entity(
        mbid: String,
        type: String,
        vararg relations: MusicBrainzUrlRelation
    ) = MusicBrainzArtist(
        id = mbid,
        name = "x",
        type = type,
        country = "DE",
        lifeSpan = MusicBrainzLifeSpan(begin = "1980-04-01"),
        beginArea = MusicBrainzArea(name = "Berlin"),
        relations = relations.toList()
    )

    private fun commons() =
        CommonsImage(
            thumbUrl = "https://upload.wikimedia.org/x.jpg",
            licenceShortName = "CC BY-SA 4.0",
            artistHtml = "<a href=\"x\">Jane Doe</a>",
            descriptionUrl = "https://commons.wikimedia.org/wiki/File:X.jpg",
            size = 1_000,
            mime = "image/jpeg",
            servedOriginal = false
        )

    private suspend fun row(id: Long) = requireNotNull(artistRepository.findById(id))

    private fun enriched(field: String) =
        registry
            .find(ImporterMetrics.MUSICBRAINZ_ENRICHED)
            .tag("field", field)
            .counter()
            ?.count() ?: 0.0

    // Block bodies, not `= runBlocking { … }`: an expression body whose last statement returns
    // non-Unit makes JUnit silently skip the test.
    @Test
    fun `fills the links, the type and the picture of a group, and does not queue the row for another lookup`() {
        runBlocking {
            val id = exact("Neubauten")
            coEvery { musicBrainz.artist("mbid-neubauten") } returns
                entity(
                    "mbid-neubauten",
                    "Group",
                    relation("official homepage", "https://neubauten.org/"),
                    relation("social network", "https://twitter.com/neubautenorg"),
                    relation("social network", "https://www.instagram.com/neubautenorg/"),
                    relation("wikidata", "https://www.wikidata.org/wiki/Q11898")
                )
            coEvery { wikimedia.imageFor("Q11898") } returns commons()

            service().enrichFor(source(), setOf(id)) shouldBe 1

            val stored = row(id)
            stored.name shouldBe "Neubauten"
            stored.websiteUrl shouldBe "https://neubauten.org/"
            stored.instagramUrl shouldBe "https://www.instagram.com/neubautenorg/"
            stored.facebookUrl.shouldBeNull()
            stored.wikidataUrl shouldBe "https://www.wikidata.org/wiki/Q11898"
            stored.artistType shouldBe "GROUP"
            stored.founded shouldBe "1980-04-01"
            stored.foundedIn shouldBe "Berlin"
            stored.country shouldBe "DE"
            stored.imageUrl shouldBe "https://upload.wikimedia.org/x.jpg"
            stored.imageAttribution shouldBe "Jane Doe, via Wikimedia Commons"
            stored.imageLicenceId shouldBe "CC-BY-SA-4.0"
            stored.imageSourceUrl shouldBe "https://commons.wikimedia.org/wiki/File:X.jpg"
            stored.musicbrainzEnrichedAt.shouldNotBeNull()
            stored.musicbrainzMatch shouldBe MusicBrainzMatch.EXACT.name
            stored.musicbrainzId shouldBe "mbid-neubauten"
            enriched("image") shouldBe 1.0
            enriched("founded") shouldBe 1.0

            // The trigger moved updated_at on this write too; equal to checked_at, so neither sweep owes the row.
            artistRepository.findNeedingMusicBrainzLookup(setOf(id)).toList() shouldBe emptyList()
            artistRepository.findNeedingMusicBrainzEnrichment(setOf(id)).toList() shouldBe emptyList()
        }
    }

    @Test
    fun `a person keeps founded null, and the CHECK refuses it even when written by hand`() {
        runBlocking {
            val id = exact("Hausswolff")
            coEvery { musicBrainz.artist("mbid-hausswolff") } returns entity("mbid-hausswolff", "Person")

            service().enrichFor(source(), setOf(id)) shouldBe 1

            val stored = row(id)
            stored.artistType shouldBe "PERSON"
            stored.founded.shouldBeNull()
            stored.foundedIn.shouldBeNull()
            stored.country shouldBe "DE"

            shouldThrow<DataIntegrityViolationException> { store.store(id, mapOf("founded" to "1986-09-06")) }
        }
    }

    @Test
    fun `a value a person set survives, and a second run reads nothing`() {
        runBlocking {
            val id = exact("Klock", websiteUrl = "https://a-person-set-this.test/")
            coEvery { musicBrainz.artist("mbid-klock") } returns entity("mbid-klock", "Person", relation("official homepage", "https://benklock.example/"))

            service().enrichFor(source(), setOf(id)) shouldBe 1
            row(id).websiteUrl shouldBe "https://a-person-set-this.test/"

            service().enrichFor(source(), setOf(id)) shouldBe 0
            coVerify(exactly = 1) { musicBrainz.artist("mbid-klock") }
        }
    }

    @Test
    fun `a row matched again since its read is read again`() {
        runBlocking {
            val id = exact("Renamed")
            coEvery { musicBrainz.artist(any()) } returns entity("mbid-renamed", "Person")
            service().enrichFor(source(), setOf(id)) shouldBe 1

            artistRepository.storeMusicBrainzVerdict(id, MusicBrainzMatch.EXACT.name, "mbid-other")

            service().enrichFor(source(), setOf(id)) shouldBe 1
            coVerify(exactly = 1) { musicBrainz.artist("mbid-other") }
        }
    }

    @Test
    fun `a row that is not EXACT is never read, and the backfill drains the oldest EXACT rows`() {
        runBlocking {
            val none = artistRepository.save(ArtistEntity(name = "A Night", slug = "a-night"))
            artistRepository.storeMusicBrainzVerdict(requireNotNull(none.id), MusicBrainzMatch.NONE.name, null)
            val older = exact("Older")
            val newer = exact("Newer")
            coEvery { musicBrainz.artist(any()) } returns entity("x", "Person")

            service(maxPerRun = 1).enrichFor(source(), emptySet()) shouldBe 1

            row(older).musicbrainzEnrichedAt.shouldNotBeNull()
            row(newer).musicbrainzEnrichedAt.shouldBeNull()
            row(requireNotNull(none.id)).musicbrainzEnrichedAt.shouldBeNull()
            coVerify(exactly = 0) { musicBrainz.artist("mbid-newer") }
        }
    }

    @Test
    fun `an MBID MusicBrainz no longer has is stamped read with nothing filled`() {
        runBlocking {
            val id = exact("Gone")
            coEvery { musicBrainz.artist("mbid-gone") } returns null

            service().enrichFor(source(), setOf(id)) shouldBe 1

            val stored = row(id)
            stored.musicbrainzEnrichedAt.shouldNotBeNull()
            stored.artistType.shouldBeNull()
        }
    }

    @Test
    fun `a read that fails is counted and skipped, and the rows after it are read`() {
        runBlocking {
            val first = exact("First")
            val second = exact("Second")
            coEvery { musicBrainz.artist("mbid-first") } throws MusicBrainzUnavailableException("503 four times")
            coEvery { musicBrainz.artist("mbid-second") } returns entity("mbid-second", "Person")

            service().enrichFor(source(), setOf(first, second)) shouldBe 1

            row(first).musicbrainzEnrichedAt.shouldBeNull()
            row(second).musicbrainzEnrichedAt.shouldNotBeNull()
            enriched("error") shouldBe 1.0
        }
    }

    @Test
    fun `a refused picture leaves the row without one, and the links still land`() {
        runBlocking {
            val id = exact("Refused")
            coEvery { musicBrainz.artist("mbid-refused") } returns
                entity(
                    "mbid-refused",
                    "Person",
                    relation("bandcamp", "https://refused.bandcamp.com/"),
                    relation("wikidata", "https://www.wikidata.org/wiki/Q1")
                )
            coEvery { wikimedia.imageFor("Q1") } returns commons().copy(licenceShortName = "GFDL")

            service().enrichFor(source(), setOf(id)) shouldBe 1

            val stored = row(id)
            stored.bandcampUrl shouldBe "https://refused.bandcamp.com/"
            stored.imageUrl.shouldBeNull()
            registry
                .find(ImporterMetrics.MUSICBRAINZ_IMAGE_REFUSED)
                .tag("reason", "licence")
                .counter()
                ?.count() shouldBe 1.0
        }
    }

    @Test
    fun `a group without a description takes its Wikipedia lead in the act's own language, with the credit`() {
        runBlocking {
            val id = exact("Neubauten")
            coEvery { musicBrainz.artist("mbid-neubauten") } returns
                entity("mbid-neubauten", "Group", relation("wikidata", "https://www.wikidata.org/wiki/Q11898"))
            coEvery { wikimedia.imageFor("Q11898") } returns null
            val lead = "Einstürzende Neubauten ist eine deutsche Band aus Berlin, die 1980 gegründet wurde und Industrial-Musik spielt."
            coEvery { wikimedia.extractFor("Q11898", listOf("de", "en")) } returns
                WikipediaExtract(language = "de", text = lead, pageUrl = "https://de.wikipedia.org/wiki/Einst%C3%BCrzende_Neubauten")

            service().enrichFor(source(), setOf(id)) shouldBe 1

            val stored = row(id)
            stored.description shouldBe lead
            stored.descriptionLanguage shouldBe "de"
            stored.descriptionAttribution shouldBe "Wikipedia"
            stored.descriptionLicenceId shouldBe "CC-BY-SA-4.0"
            stored.descriptionSourceUrl shouldBe "https://de.wikipedia.org/wiki/Einst%C3%BCrzende_Neubauten"
            enriched("description") shouldBe 1.0
        }
    }

    @Test
    fun `a group whose item links no article is counted as no-article, and a person is never asked`() {
        runBlocking {
            val group = exact("Articleless")
            val person = exact("Solo")
            coEvery { musicBrainz.artist("mbid-articleless") } returns
                entity("mbid-articleless", "Group", relation("wikidata", "https://www.wikidata.org/wiki/Q2"))
            coEvery { musicBrainz.artist("mbid-solo") } returns entity("mbid-solo", "Person", relation("wikidata", "https://www.wikidata.org/wiki/Q3"))
            coEvery { wikimedia.imageFor(any()) } returns null

            service().enrichFor(source(), setOf(group, person)) shouldBe 2

            row(group).description.shouldBeNull()
            registry
                .find(ImporterMetrics.WIKIPEDIA_REFUSED)
                .tag("reason", "no-article")
                .counter()
                ?.count() shouldBe 1.0
            coVerify(exactly = 0) { wikimedia.extractFor("Q3", any()) }
        }
    }

    @Test
    fun `the CHECK refuses a credit without a text, and a partial credit`() {
        runBlocking {
            val id = exact("Uncredited")
            shouldThrow<DataIntegrityViolationException> {
                store.store(id, mapOf("description_attribution" to "Wikipedia", "description_licence_id" to "CC-BY-SA-4.0", "description_source_url" to "x"))
            }
            shouldThrow<DataIntegrityViolationException> {
                store.store(id, mapOf("description" to "Ein Text.", "description_attribution" to "Wikipedia"))
            }
            store.store(id, mapOf("description" to "Ein Text, den eine Person schrieb.")) shouldBe 1L
        }
    }
}
