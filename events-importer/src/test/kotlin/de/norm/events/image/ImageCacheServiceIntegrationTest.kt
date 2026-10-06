package de.norm.events.image

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.norm.events.BaseControllerTest
import de.norm.events.scraper.ScraperHttpClientConfig
import de.norm.events.scraper.ScraperProperties
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import org.testcontainers.containers.MinIOContainer
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.imageio.ImageIO

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

    private val servers = mutableListOf<MockWebServer>()
    private val minio = MinIOContainer(MINIO_TEST_IMAGE)
    private var minioStarted = false

    @AfterEach
    fun closeServers() {
        servers.forEach { it.close() }
        servers.clear()
        if (minioStarted) minio.stop()
        minioStarted = false
    }

    /** A real S3 API, started only for the tests that store — the others need no container. */
    private fun minioStorage(): ImageStorage =
        runBlocking {
            if (!minioStarted) {
                minio.start()
                minioStarted = true
            }
            val properties =
                ImageStorageProperties(bucket = "images", prefix = "staging", endpoint = minio.s3URL, accessKey = minio.userName, secretKey = minio.password)
            val client = ImageStorageConfig().s3AsyncClient(properties)!!
            runCatching { client.createBucket(CreateBucketRequest.builder().bucket("images").build()).await() }
            ImageStorage(client, properties)
        }

    private suspend fun objectExists(key: String): Boolean {
        val properties =
            ImageStorageProperties(bucket = "images", prefix = "staging", endpoint = minio.s3URL, accessKey = minio.userName, secretKey = minio.password)
        val client = ImageStorageConfig().s3AsyncClient(properties)!!
        return runCatching {
            client
                .headObject(
                    software.amazon.awssdk.services.s3.model.HeadObjectRequest
                        .builder()
                        .bucket("images")
                        .key(key)
                        .build()
                ).await()
        }.isSuccess
    }

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
    fun `offers the venue, artist and promoter images too, not only the events'`(): Unit =
        runBlocking {
            // These three columns are written by the admin API and no scraper touches them, so no
            // `event_source` licence applies (#833). Missing them left four render sites hotlinking
            // after the event path had stopped.
            databaseClient.sql("UPDATE events.venue SET image_url = 'https://venue.test/logo.jpg', $IMAGE_CREDIT_SET").await()
            insertArtist("https://artist.test/photo.jpg")
            insertPromoter("https://promoter.test/logo.jpg")

            repository.findUncachedImageUrls(100).toList() shouldContainExactlyInAnyOrder
                listOf(
                    "https://venue.test/poster-one.jpg",
                    "https://venue.test/poster-two.jpg",
                    "https://venue.test/logo.jpg",
                    "https://artist.test/photo.jpg",
                    "https://promoter.test/logo.jpg"
                )
        }

    @Test
    fun `one URL on an event and on its venue is a single fetch`(): Unit =
        runBlocking {
            // `UNION` rather than `UNION ALL`, so the same file under two columns is one object.
            databaseClient.sql("UPDATE events.venue SET image_url = 'https://venue.test/poster-one.jpg', $IMAGE_CREDIT_SET").await()

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
            metrics = ImageCacheMetrics(SimpleMeterRegistry()),
            clock = Clock.systemUTC()
        )

    // --- the success path, which needs a server to fetch from and a bucket to store in -----------

    @Test
    fun `fetches, stores and records an image in one pass`(): Unit =
        runBlocking {
            // The path every other test here avoids, and the one that matters: a real HTTP response,
            // a real S3 put, and the row that says both happened. It is why MinIO is a container
            // rather than a mock (ADR-019 §2.9).
            val png = pngBytes()
            val server = MockWebServer().also { it.start() }
            servers += server
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .setHeader("Content-Type", "image/png")
                    .setHeader("ETag", "\"v1\"")
                    .body(Buffer().write(png))
                    .build()
            )
            insertEvent("e", server.url("/poster.png").toString())

            val outcome = storingService().refreshBatch()

            outcome.fetched shouldBe 1
            val row = repository.findAll().toList().single { it.contentHash != null }
            row.contentType shouldBe "image/png"
            row.byteSize shouldBe png.size.toLong()
            row.intrinsicWidth shouldBe 12
            row.etag shouldBe "\"v1\""
            row.failedAt.shouldBeNull()
            // And the object is actually in the bucket, not merely claimed by the row.
            objectExists("staging/originals/${row.contentHash}") shouldBe true
        }

    @Test
    fun `a 304 keeps the bytes, counts as unchanged and is not due again`(): Unit =
        runBlocking {
            val server = MockWebServer().also { it.start() }
            servers += server
            server.enqueue(MockResponse.Builder().code(304).build())
            val url = server.url("/poster.png").toString()
            // Truncated to what PostgreSQL stores, so the round trip compares equal.
            val fetchedAt = Instant.now().minus(Duration.ofDays(400)).truncatedTo(ChronoUnit.MICROS)
            repository.save(
                CachedImageEntity(
                    sourceUrl = url,
                    contentHash = "kept",
                    contentType = "image/png",
                    etag = "\"v1\"",
                    fetchedAt = fetchedAt,
                    lastSeenAt = fetchedAt
                )
            )

            val outcome = storingService().refreshBatch()

            outcome.unchanged shouldBe 1
            // The hash must survive a 304, or an unchanged image would lose the object it points at.
            val row = repository.findBySourceUrl(url)!!
            row.contentHash shouldBe "kept"
            row.fetchedAt shouldBe fetchedAt
            // A 304 moves the refresh clock, so the next pass asks about other images (#2785).
            dueNow().map { it.sourceUrl } shouldNotContain url
        }

    @Test
    fun `a success not asked about for the refresh window is due, oldest question first`(): Unit =
        runBlocking {
            val now = Instant.now()
            val older = saveFetched("https://venue.test/older.jpg", lastSeenAt = now.minus(Duration.ofDays(40)))
            val old = saveFetched("https://venue.test/old.jpg", lastSeenAt = now.minus(Duration.ofDays(31)))
            // Downloaded long ago, but asked yesterday and answered 304. Not due.
            saveFetched("https://venue.test/asked.jpg", lastSeenAt = now.minus(Duration.ofDays(1)))

            dueNow().map { it.sourceUrl } shouldBe listOf(older.sourceUrl, old.sourceUrl)
        }

    @Test
    fun `a refused image that once downloaded waits out the retry cooldown`(): Unit =
        runBlocking {
            // Its old `fetched_at` used to make it due again on the very next pass, so one dead
            // image was asked for every five minutes.
            val now = Instant.now()
            saveFetched("https://venue.test/gone.jpg", lastSeenAt = now.minus(Duration.ofDays(1)), failedAt = now.minus(Duration.ofDays(1)))
            val cooled =
                saveFetched("https://venue.test/back.jpg", lastSeenAt = now.minus(Duration.ofDays(8)), failedAt = now.minus(Duration.ofDays(8)))

            dueNow().map { it.sourceUrl } shouldBe listOf(cooled.sourceUrl)
        }

    @Test
    fun `each refusal is logged at WARN with the host and the reason`(): Unit =
        runBlocking {
            val logger = LoggerFactory.getLogger(ImageCacheService::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                service(ImageProperties(fetchEnabled = true)).refreshBatch()
            } finally {
                logger.detachAppender(appender)
            }

            val warnings = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
            warnings.size shouldBe 2
            warnings.forEach {
                it shouldStartWith "Image refused by venue.test: "
                // The path is not needed to act on it, and the row holds the full URL.
                it shouldNotContain "poster"
            }
        }

    private suspend fun dueNow(): List<CachedImageEntity> {
        val properties = ImageProperties()
        val now = Instant.now()
        return repository
            .findDueForRefresh(
                refreshBefore = now.minus(properties.refreshAfter),
                retryBefore = now.minus(properties.retryFailedAfter),
                limit = 100
            ).toList()
    }

    private suspend fun saveFetched(
        url: String,
        lastSeenAt: Instant,
        failedAt: Instant? = null
    ) = repository.save(
        CachedImageEntity(
            sourceUrl = url,
            contentHash = "hash-$url",
            fetchedAt = lastSeenAt.minus(Duration.ofDays(400)),
            lastSeenAt = lastSeenAt,
            failedAt = failedAt,
            failureReason = failedAt?.let { "HTTP 404" }
        )
    )

    private fun storingService(): ImageCacheService {
        val properties = ImageProperties(fetchEnabled = true)
        val scraper = ScraperProperties(politeDelayMillis = 0)
        val config = ScraperHttpClientConfig()
        return ImageCacheService(
            repository = repository,
            fetcher =
                ImageFetcher(
                    webClient =
                        config.scraperBaseWebClient(
                            webClientBuilder =
                                org.springframework.web.reactive.function.client.WebClient
                                    .builder(),
                            scraperProperties = scraper,
                            connectionProvider = config.scraperConnectionProvider(scraper),
                            throttle = config.perHostThrottlingFilter(scraper)
                        ),
                    ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
                    // MockWebServer listens on loopback, which the real guard refuses by design.
                    validator =
                        object : ImageUrlValidator() {
                            override fun reject(url: String): String? = null
                        },
                    properties = properties
                ),
            storage = minioStorage(),
            properties = properties,
            metrics = ImageCacheMetrics(SimpleMeterRegistry()),
            clock = Clock.systemUTC()
        )
    }

    private fun pngBytes(): ByteArray =
        ByteArrayOutputStream()
            .also { ImageIO.write(BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB), "png", it) }
            .toByteArray()

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

    private suspend fun insertArtist(imageUrl: String) =
        databaseClient
            .sql(
                "INSERT INTO events.artist (name, slug, image_url, $IMAGE_CREDIT_COLUMNS) " +
                    "VALUES ('Act', 'act', '$imageUrl', $IMAGE_CREDIT_VALUES)"
            ).await()

    private suspend fun insertPromoter(imageUrl: String) =
        databaseClient
            .sql(
                "INSERT INTO events.promoter (name, slug, image_url, $IMAGE_CREDIT_COLUMNS) " +
                    "VALUES ('Promo', 'promo', '$imageUrl', $IMAGE_CREDIT_VALUES)"
            ).await()

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
