package de.norm.events.image

import de.norm.events.BaseControllerTest
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import java.time.Clock

/**
 * [ImageCacheService] and its raw SQL, against a real database.
 *
 * The URL query is `@Query` SQL in an annotation, so a unit test can only assert what a mock was
 * told to return. What matters is which rows PostgreSQL hands back — in particular that one poster
 * on two events is one fetch, not two.
 */
class ImageCacheServiceIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var repository: CachedImageRepository

    private var venueId: Long = 0
    private var sourceId: Long = 0

    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            venueId = insertVenue()
            sourceId = insertSource()
            insertEvent("a", "https://venue.test/poster-one.jpg")
            // The same poster on a second event. One row, one fetch.
            insertEvent("b", "https://venue.test/poster-one.jpg")
            insertEvent("c", "https://venue.test/poster-two.jpg")
            insertEvent("d", null)
        }

    @Test
    fun `finds each distinct image URL once and ignores events with none`(): Unit =
        runBlocking {
            repository.findUncachedImageUrls(100).toList() shouldContainExactlyInAnyOrder
                listOf("https://venue.test/poster-one.jpg", "https://venue.test/poster-two.jpg")
        }

    @Test
    fun `a URL already cached is not offered again`(): Unit =
        runBlocking {
            repository.save(CachedImageEntity(sourceUrl = "https://venue.test/poster-one.jpg"))

            repository.findUncachedImageUrls(100).toList() shouldBe listOf("https://venue.test/poster-two.jpg")
        }

    @Test
    fun `the disabled flag fetches nothing`(): Unit =
        runBlocking {
            // The default. Off means no request leaves the process, so CI and a laptop without
            // Docker are unaffected by this module existing (ADR-019 §2.10).
            val service = service(ImageProperties(fetchEnabled = false))

            service.refreshBatch().total shouldBe 0
            repository.count() shouldBe 0
        }

    @Test
    fun `a refusal is written as a negative cache row rather than dropped`(): Unit =
        runBlocking {
            // Every URL here is unroutable, so the real validator refuses both. That is the point:
            // a URL that cannot produce an image must still leave a row, or it is retried nightly
            // forever (ADR-019 §3.6).
            val outcome = service(ImageProperties(fetchEnabled = true)).refreshBatch()

            outcome.failed shouldBe 2
            outcome.fetched shouldBe 0
            val rows = repository.findAll().toList()
            rows.size shouldBe 2
            rows.forEach {
                it.failedAt.shouldNotBeNull()
                // The reason is stored so a blank card can be explained without re-running anything.
                it.failureReason.shouldNotBeNull()
                it.contentHash shouldBe null
            }
        }

    private fun service(properties: ImageProperties) =
        ImageCacheService(
            repository = repository,
            fetcher =
                ImageFetcher(
                    webClient =
                        org.springframework.web.reactive.function.client.WebClient
                            .builder()
                            .build(),
                    ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
                    validator = ImageUrlValidator(),
                    properties = properties
                ),
            // No client, so `isEnabled()` is false: the service records rows and stores nothing,
            // which is the state a local run without bucket credentials is in.
            storage = ImageStorage(client = null, properties = ImageStorageProperties()),
            properties = properties,
            clock = Clock.systemUTC()
        )

    // --- seeding -------------------------------------------------------------------------------

    private suspend fun insertVenue(): Long =
        databaseClient
            .sql(
                "INSERT INTO events.venue (name, slug, address, city, postal_code) " +
                    "VALUES ('Test Venue', 'test-venue', 'Somewhere 1', 'Berlin', '10999') RETURNING id"
            ).map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun insertSource(): Long =
        databaseClient
            .sql(
                """
                INSERT INTO events.event_source (venue_id, name, slug, url, source_type)
                VALUES ($venueId, 'test', 'test', 'https://venue.test/events', 'CASSIOPEIA') RETURNING id
                """.trimIndent()
            ).map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun insertEvent(
        key: String,
        imageUrl: String?
    ) = databaseClient
        .sql(
            """
            INSERT INTO events.event
                (venue_id, event_source_id, source_id, title, slug, event_date, image_url, event_type, status)
            VALUES ($venueId, $sourceId, 'test:$key', 'Show $key', 'show-$key', CURRENT_DATE + 10,
                    ${imageUrl?.let { "'$it'" } ?: "NULL"}, 'CONCERT', 'SCHEDULED')
            """.trimIndent()
        ).await()
}
