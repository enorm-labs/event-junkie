package de.norm.events.image

import de.norm.events.BaseControllerTest
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import org.testcontainers.containers.MinIOContainer
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The takedown and the sweep, against a real database and a real S3 API.
 *
 * **Both halves are joins, and a join is what a mock agrees with.** The takedown reaches a venue's
 * images through its events, and the sweep decides what nothing points at; a stub returning a
 * prepared list would assert the test's own SQL rather than PostgreSQL's.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImageRemovalServiceIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var repository: CachedImageRepository

    @Autowired
    private lateinit var variantRepository: CachedImageVariantRepository

    private val minio = MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z")
    private lateinit var client: S3AsyncClient
    private lateinit var storageProperties: ImageStorageProperties

    @BeforeAll
    fun startBucket(): Unit =
        runBlocking {
            minio.start()
            storageProperties =
                ImageStorageProperties(
                    bucket = "images",
                    prefix = "staging",
                    endpoint = minio.s3URL,
                    accessKey = minio.userName,
                    secretKey = minio.password
                )
            client = ImageStorageConfig().s3AsyncClient(storageProperties)!!
            client.createBucket(CreateBucketRequest.builder().bucket("images").build()).await()
        }

    @AfterAll
    fun stopBucket() = minio.stop()

    // --- the takedown ---------------------------------------------------------------------------

    @Test
    fun `takes down the images of one venue and leaves another venue's alone`(): Unit =
        runBlocking {
            emptyBucket()
            val mine = seedVenue("cassiopeia")
            val theirs = seedVenue("astra")
            val taken = seedImage("aaa", "https://mine.test/one.jpg", mine)
            seedImage("bbb", "https://theirs.test/one.jpg", theirs)

            val outcome = service().takeDown("cassiopeia")

            outcome.images shouldBe 1
            // Two derivatives and the original.
            outcome.objects shouldBe 3
            repository.findById(taken)!!.deletedAt.shouldNotBeNull()
            repository.findBySourceUrl("https://theirs.test/one.jpg")!!.deletedAt.shouldBeNull()
            objectExists("staging/originals/aaa") shouldBe false
            objectExists("staging/originals/bbb") shouldBe true
        }

    @Test
    fun `a takedown removes the variant rows as well as the objects`(): Unit =
        runBlocking {
            emptyBucket()
            val venue = seedVenue("cassiopeia")
            val id = seedImage("aaa", "https://mine.test/one.jpg", venue)

            service().takeDown("cassiopeia")

            variantRepository.findByCachedImageId(id).toList() shouldBe emptyList()
        }

    @Test
    fun `an image two venues publish keeps its object until the last row goes`(): Unit =
        runBlocking {
            // Keys are the hash of the bytes, so one file under two URLs is one object. Deleting it
            // for the first venue would blank the second's card and leave its row claiming it.
            emptyBucket()
            val mine = seedVenue("cassiopeia")
            val theirs = seedVenue("astra")
            seedImage("shared", "https://mine.test/poster.jpg", mine)
            seedImage("shared", "https://theirs.test/poster.jpg", theirs, storeObjects = false)

            service().takeDown("cassiopeia").objects shouldBe 0

            objectExists("staging/originals/shared") shouldBe true
        }

    @Test
    fun `a venue with nothing cached is a takedown of nothing, not a failure`(): Unit =
        runBlocking {
            emptyBucket()
            seedVenue("cassiopeia")

            service().takeDown("cassiopeia").images shouldBe 0
        }

    // --- the sweep ------------------------------------------------------------------------------

    @Test
    fun `the sweep deletes a row no event points at, and its objects`(): Unit =
        runBlocking {
            emptyBucket()
            val venue = seedVenue("cassiopeia")
            val id = seedImage("aaa", "https://mine.test/one.jpg", venue)
            // What stale-event cleanup and a PROHIBITED image licence both leave behind.
            databaseClient.sql("UPDATE events.event SET image_url = NULL").await()

            val outcome = service().sweep()

            outcome.images shouldBe 1
            outcome.objects shouldBe 3
            repository.findById(id).shouldBeNull()
            objectExists("staging/originals/aaa") shouldBe false
        }

    @Test
    fun `the sweep leaves a venue, artist or promoter image alone`(): Unit =
        runBlocking {
            // **The regression this pins is a loop, not a one-off.** The fetcher offers all four
            // columns; a sweep that asks only `event` calls every one of the other three an orphan,
            // deletes it, and the next pass stores it again (#833).
            emptyBucket()
            val venue = seedVenue("cassiopeia")
            seedImage("vvv", "https://mine.test/logo.jpg", venue, withEvent = false)
            databaseClient.sql("UPDATE events.venue SET image_url = 'https://mine.test/logo.jpg' WHERE id = $venue").await()

            service().sweep().images shouldBe 0

            objectExists("staging/originals/vvv") shouldBe true
        }

    @Test
    fun `a takedown covers the venue's own image, not only its events'`(): Unit =
        runBlocking {
            emptyBucket()
            val venue = seedVenue("cassiopeia")
            seedImage("vvv", "https://mine.test/logo.jpg", venue, withEvent = false)
            databaseClient.sql("UPDATE events.venue SET image_url = 'https://mine.test/logo.jpg' WHERE id = $venue").await()

            service().takeDown("cassiopeia").images shouldBe 1

            objectExists("staging/originals/vvv") shouldBe false
        }

    @Test
    fun `the sweep never drops a tombstone, so a takedown is not undone`(): Unit =
        runBlocking {
            emptyBucket()
            val venue = seedVenue("cassiopeia")
            val id = seedImage("aaa", "https://mine.test/one.jpg", venue)
            service().takeDown("cassiopeia")
            databaseClient.sql("UPDATE events.event SET image_url = NULL").await()

            service().sweep().images shouldBe 0

            // The row has to stay, or the next import fetches the image the venue asked us to drop.
            repository.findById(id).shouldNotBeNull()
        }

    @Test
    fun `the sweep deletes an object no row claims once it is past the grace period`(): Unit =
        runBlocking {
            emptyBucket()
            val venue = seedVenue("cassiopeia")
            seedImage("aaa", "https://mine.test/one.jpg", venue)
            // A put that succeeded while the row that would have named it did not save. Nothing in
            // the database remembers it, so only the listing can find it.
            put("staging/originals/lost")

            val outcome = service(now = Instant.now().plus(Duration.ofDays(2))).sweep()

            outcome.strays shouldBe 1
            objectExists("staging/originals/lost") shouldBe false
            objectExists("staging/originals/aaa") shouldBe true
        }

    @Test
    fun `a freshly stored object is left alone, because its row may not be written yet`(): Unit =
        runBlocking {
            emptyBucket()
            val venue = seedVenue("cassiopeia")
            seedImage("aaa", "https://mine.test/one.jpg", venue)
            put("staging/originals/lost")

            service().sweep().strays shouldBe 0

            objectExists("staging/originals/lost") shouldBe true
        }

    @Test
    fun `the sweep refuses a full bucket that no row claims at all`(): Unit =
        runBlocking {
            // A database that did not answer looks exactly like a cache that is empty, and acting on
            // it would delete every image we hold.
            emptyBucket()
            put("staging/originals/aaa")
            put("staging/derived/aaa/192.avif")

            service(now = Instant.now().plus(Duration.ofDays(2))).sweep().strays shouldBe 0

            objectExists("staging/originals/aaa") shouldBe true
        }

    @Test
    fun `a key this environment did not write is never swept`(): Unit =
        runBlocking {
            emptyBucket()
            val venue = seedVenue("cassiopeia")
            seedImage("aaa", "https://mine.test/one.jpg", venue)
            put("staging/something-else/file.bin")

            service(now = Instant.now().plus(Duration.ofDays(2))).sweep().strays shouldBe 0

            objectExists("staging/something-else/file.bin") shouldBe true
        }

    @Test
    fun `the reporting mode counts what it would delete and deletes nothing`(): Unit =
        runBlocking {
            emptyBucket()
            val venue = seedVenue("cassiopeia")
            val id = seedImage("aaa", "https://mine.test/one.jpg", venue)
            databaseClient.sql("UPDATE events.event SET image_url = NULL").await()

            val outcome = service(enabled = false).sweep()

            outcome.images shouldBe 1
            outcome.objects shouldBe 3
            repository.findById(id).shouldNotBeNull()
            objectExists("staging/originals/aaa") shouldBe true
        }

    @Test
    fun `a takedown deletes even while the sweep only reports`(): Unit =
        runBlocking {
            // The switch exists to watch a scheduled rule before trusting it. An operator asking for
            // their images to go is not a rule being watched.
            emptyBucket()
            val venue = seedVenue("cassiopeia")
            seedImage("aaa", "https://mine.test/one.jpg", venue)

            service(enabled = false).takeDown("cassiopeia").objects shouldBe 3

            objectExists("staging/originals/aaa") shouldBe false
        }

    @Test
    fun `a listing only sees this environment's prefix`(): Unit =
        runBlocking {
            emptyBucket()
            put("staging/originals/mine")
            put("production/originals/theirs")

            storage().listAll().map { it.key } shouldContainExactlyInAnyOrder listOf("staging/originals/mine")
        }

    // --- fixtures -------------------------------------------------------------------------------

    private fun storage() = ImageStorage(client, storageProperties)

    private fun service(
        enabled: Boolean = true,
        now: Instant = Instant.now()
    ) = ImageRemovalService(
        repository = repository,
        variantRepository = variantRepository,
        storage = storage(),
        properties = ImageSweepProperties(enabled = enabled),
        clock = Clock.fixed(now, java.time.ZoneOffset.UTC)
    )

    private suspend fun seedVenue(slug: String): Long {
        val venueId =
            databaseClient
                .sql(
                    "INSERT INTO events.venue (name, slug, address, city, postal_code) " +
                        "VALUES ('$slug', '$slug', 'Somewhere 1', 'Berlin', '10999') RETURNING id"
                ).map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
                .awaitSingle()
        databaseClient
            .sql(
                """
                INSERT INTO events.event_source (venue_id, name, slug, url, source_type)
                VALUES ($venueId, '$slug', '$slug', 'https://$slug.test/events', 'CASSIOPEIA')
                """.trimIndent()
            ).await()
        return venueId
    }

    /** One cached image with two derivatives, an event pointing at it, and the objects to match. */
    private suspend fun seedImage(
        contentHash: String,
        sourceUrl: String,
        venueId: Long,
        storeObjects: Boolean = true,
        withEvent: Boolean = true
    ): Long {
        if (withEvent) {
            databaseClient
                .sql(
                    """
                    INSERT INTO events.event (venue_id, source_id, title, slug, event_date, image_url, event_type, status)
                    VALUES ($venueId, '$sourceUrl', 'Show', 'show-$venueId-${contentHash.hashCode()}',
                            CURRENT_DATE + 10, '$sourceUrl', 'CONCERT', 'SCHEDULED')
                    """.trimIndent()
                ).await()
        }
        val saved = repository.save(CachedImageEntity(sourceUrl = sourceUrl, contentHash = contentHash, fetchedAt = Instant.now()))
        val id = saved.id!!

        listOf(192 to "avif", 288 to "webp").forEach { (width, format) ->
            val key = storage().derivativeKey(contentHash, width, format)
            variantRepository.save(CachedImageVariantEntity(cachedImageId = id, width = width, format = format, storageKey = key, byteSize = 1))
            if (storeObjects) put(key)
        }
        if (storeObjects) put(storage().originalKey(contentHash))
        return id
    }

    private suspend fun put(key: String) {
        client
            .putObject(
                PutObjectRequest
                    .builder()
                    .bucket("images")
                    .key(key)
                    .build(),
                AsyncRequestBody.fromBytes(byteArrayOf(1))
            ).await()
    }

    private suspend fun objectExists(key: String): Boolean =
        runCatching {
            client
                .headObject(
                    HeadObjectRequest
                        .builder()
                        .bucket("images")
                        .key(key)
                        .build()
                ).await()
        }.isSuccess

    /** The bucket outlives one test, unlike the database, so each test starts by clearing it. */
    private suspend fun emptyBucket() {
        val keys =
            client
                .listObjectsV2(ListObjectsV2Request.builder().bucket("images").build())
                .await()
                .contents()
                .map { it.key() }
        ImageStorage(client, storageProperties).delete(keys)
    }
}
