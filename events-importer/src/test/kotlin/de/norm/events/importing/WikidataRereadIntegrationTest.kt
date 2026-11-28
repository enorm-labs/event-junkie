package de.norm.events.importing

import de.norm.events.BaseControllerTest
import de.norm.events.artist.ArtistGenreRepository
import de.norm.events.artist.ArtistOccupationRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.awaitSingle
import java.time.Duration
import java.time.Instant

/** #5253 against a real PostgreSQL: both Wikidata queues take the unread rows, then the stale ones, oldest first. */
class WikidataRereadIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var occupations: ArtistOccupationRepository

    @Autowired
    private lateinit var genres: ArtistGenreRepository

    private val now = Instant.now()
    private val staleBefore = now.minus(Duration.ofDays(90))

    @Test
    fun `the genre queue takes an unread row before a stale one, the oldest stale first, and skips a fresh one`(): Unit =
        runBlocking {
            artist("fresh", genres = "{Q8341}", readDaysAgo = 10)
            artist("stale-old", genres = "{Q8341}", readDaysAgo = 200)
            artist("stale", genres = "{}", readDaysAgo = 100)
            artist("never-stamped", genres = "{Q8341}", readDaysAgo = null)
            artist("unread", genres = null, readDaysAgo = null)
            artist("unread-stamped", genres = null, readDaysAgo = 5)

            genres.findGenreReads(10, staleBefore).map { it.slug }.toList() shouldBe
                listOf("unread", "unread-stamped", "never-stamped", "stale-old", "stale")
        }

    @Test
    fun `the occupation queue takes an unread row first, and a stale one only once its genres are read`(): Unit =
        runBlocking {
            artist("fresh", comedian = false, genres = "{}", readDaysAgo = 10)
            artist("stale", comedian = true, genres = "{}", readDaysAgo = 100)
            artist("never-stamped", comedian = false, genres = "{Q8341}", readDaysAgo = null)
            artist("genres-unread", comedian = false, genres = null, readDaysAgo = null)
            artist("unread", comedian = null, genres = null, readDaysAgo = null)

            occupations.findOccupationReads(10, staleBefore).map { it.slug }.toList() shouldBe
                listOf("unread", "never-stamped", "stale")
        }

    @Test
    fun `the genre store stamps the read, and the occupation store leaves the stamp alone`(): Unit =
        runBlocking {
            val id = artist("act", comedian = false, genres = "{}", readDaysAgo = 200)

            occupations.storeComedian(id, true)
            readDaysAgo("act") shouldBe 200

            genres.storeGenres(id, arrayOf("Q8341"))
            readDaysAgo("act") shouldBe 0
            genres.findGenreReads(10, staleBefore).toList() shouldBe emptyList()
            occupations.findOccupationReads(10, staleBefore).toList() shouldBe emptyList()
        }

    private suspend fun artist(
        slug: String,
        comedian: Boolean? = false,
        genres: String?,
        readDaysAgo: Long?
    ): Long =
        databaseClient
            .sql(
                "INSERT INTO events.artist (name, slug, musicbrainz_match, musicbrainz_id, wikidata_url, comedian, wikidata_genres, wikidata_read_at) " +
                    "VALUES (:slug, :slug, 'EXACT', :mbid, :wikidata, :comedian, CAST(:genres AS TEXT[]), " +
                    // The database clock writes the stamp that readDaysAgo reads back with now() (#5659).
                    "now() - make_interval(days => CAST(:readDays AS INT))) RETURNING id"
            ).bind("slug", slug)
            .bind("mbid", "mbid-$slug")
            .bind("wikidata", "https://www.wikidata.org/wiki/Q-$slug")
            .let { spec -> comedian?.let { spec.bind("comedian", it) } ?: spec.bindNull("comedian", Boolean::class.javaObjectType) }
            .let { spec -> genres?.let { spec.bind("genres", it) } ?: spec.bindNull("genres", String::class.java) }
            .let { spec ->
                readDaysAgo?.let { spec.bind("readDays", it.toInt()) } ?: spec.bindNull("readDays", Int::class.javaObjectType)
            }.map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun readDaysAgo(slug: String): Long =
        databaseClient
            .sql("SELECT EXTRACT(DAY FROM now() - wikidata_read_at)::bigint AS days FROM events.artist WHERE slug = '$slug'")
            .map { row, _ -> row.get("days", Number::class.java)!!.toLong() }
            .awaitSingle()
}
