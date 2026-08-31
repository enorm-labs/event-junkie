package de.norm.events.image

import de.norm.events.BaseControllerTest
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MinIOContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The serving path end to end, with serving switched on.
 *
 * **A real S3 API rather than a mocked client.** What has to hold is that a key the importer wrote
 * reads back through this application's own configuration — path-style access, the signing region,
 * the endpoint override — and a mock proves none of it. It is the same argument the importer's
 * `ImageStorageIntegrationTest` makes from the writing side.
 */
class CachedImageServingTest : BaseControllerTest() {
    @Autowired
    private lateinit var registry: MeterRegistry

    @Test
    fun `serves the bytes behind a variant row`(): Unit =
        runBlocking {
            insertCachedImage(POSTER_URL, HASH, listOf(288))
            putObject(derivedKey(HASH, 288, "jpg"), POSTER)

            webTestClient
                .get()
                .uri("/images/$HASH/288.jpg")
                .exchange()
                .expectStatus()
                .isOk
                .expectHeader()
                .contentType(MediaType.IMAGE_JPEG)
                // Content addressed, so the URL can only ever return these bytes.
                .expectHeader()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .expectHeader()
                .valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader()
                .valueEquals(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .expectBody()
                .consumeWith { assertContentEquals(POSTER, it.responseBody) }
        }

    @Test
    @DisplayName("two rows on one content hash still serve, rather than 500")
    fun `a content hash held by more than one row is served`(): Unit =
        runBlocking {
            // What 24 of production's 1118 images looked like: a row is keyed by `source_url`, so
            // byte-identical files published under two URLs get two live rows on one hash. The
            // variant key derives from the hash alone, so both rows name the same object.
            insertCachedImage(POSTER_URL, DUPLICATE_HASH, listOf(288))
            insertCachedImage(ALTERNATE_URL, DUPLICATE_HASH, listOf(288))
            putObject(derivedKey(DUPLICATE_HASH, 288, "jpg"), POSTER)

            webTestClient
                .get()
                .uri("/images/$DUPLICATE_HASH/288.jpg")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .consumeWith { assertContentEquals(POSTER, it.responseBody) }
        }

    @Test
    @DisplayName("a width the generator never produced is a 404, not a resize")
    fun `an unknown variant is not found`(): Unit =
        runBlocking {
            insertCachedImage(POSTER_URL, HASH, listOf(288))

            webTestClient
                .get()
                .uri("/images/$HASH/1536.jpg")
                .exchange()
                .expectStatus()
                .isNotFound
        }

    @Test
    @DisplayName("a takedown stops the bytes immediately, before any object is swept")
    fun `a deleted image is not served`(): Unit =
        runBlocking {
            insertCachedImage(POSTER_URL, HASH, listOf(288), deleted = true)
            putObject(derivedKey(HASH, 288, "jpg"), POSTER)

            webTestClient
                .get()
                .uri("/images/$HASH/288.jpg")
                .exchange()
                .expectStatus()
                .isNotFound
        }

    @Test
    @DisplayName("a row pointing at an object that is gone is a 404, not a broken 200")
    fun `a missing object is not found`(): Unit =
        runBlocking {
            // The shape of a sweep that deleted an object it should have kept. It has to read as
            // absent rather than as a truncated image, and the warning it logs is what names it. Its
            // own hash, because the bucket is not truncated between tests and the key derives from it.
            insertCachedImage(POSTER_URL, ORPHAN_HASH, listOf(288))

            webTestClient
                .get()
                .uri("/images/$ORPHAN_HASH/288.jpg")
                .exchange()
                .expectStatus()
                .isNotFound
        }

    /**
     * **Two of these are 404s and they mean opposite things**, which is why the outcome is recorded
     * in the controller rather than derived from `http_server_requests`: a path nobody published,
     * and a row promising an object the bucket does not have. Only the second is a defect.
     *
     * Counted as deltas, because the registry belongs to the shared context and the other tests in
     * this class serve images through it too.
     */
    @Test
    fun `each ending is counted under its own outcome`(): Unit =
        runBlocking {
            insertCachedImage(POSTER_URL, HASH, listOf(288))
            // A second URL, because `cached_image.source_url` is unique — the same property the
            // repository's counting query relies on to join at most one row per URL.
            insertCachedImage(ALTERNATE_URL, ORPHAN_HASH, listOf(288))
            putObject(derivedKey(HASH, 288, "jpg"), POSTER)
            val before = OUTCOMES.associateWith { served(it) }

            listOf("/images/$HASH/288.jpg", "/images/$ORPHAN_HASH/288.jpg", "/images/nothex/288.jpg").forEach {
                webTestClient.get().uri(it).exchange()
            }

            OUTCOMES.forEach { assertEquals(1.0, served(it) - before.getValue(it), "outcome=$it") }
        }

    private fun served(outcome: String): Double =
        registry
            .find("bff.images.served")
            .tag("outcome", outcome)
            .counter()
            ?.count() ?: 0.0

    @Test
    fun `a malformed file name never reaches the database`(): Unit =
        runBlocking {
            insertCachedImage(POSTER_URL, HASH, listOf(288))

            // Each of these matches neither the hash nor the `<width>.<format>` shape, so the route
            // refuses it before the query. A path segment is what an attacker would try first.
            listOf("/images/$HASH/288.svg", "/images/$HASH/288", "/images/$HASH/-1.jpg", "/images/nothex/288.jpg")
                .forEach {
                    webTestClient
                        .get()
                        .uri(it)
                        .exchange()
                        .expectStatus()
                        .isNotFound
                }
        }

    // --- the substitution, which is what a browser actually sees --------------------------------

    @Test
    @DisplayName("an event's imageUrl becomes a path on our own origin")
    fun `the detail response carries our url`(): Unit =
        runBlocking {
            val venueId = insertVenue("Lido", "lido")
            insertEvent(venueId, "Show", "show", LocalDate.now().plusDays(3), imageUrl = POSTER_URL)
            insertCachedImage(POSTER_URL, HASH, ALL_WIDTHS)

            webTestClient
                .get()
                .uri("/events/show")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                // 768 first, because EventDetailView draws the image across a 704 px column.
                .jsonPath("$.imageUrl")
                .isEqualTo("/api/images/$HASH/768.jpg")
                // Best format first, and the widths banded to the slot. The card list gets 192 and
                // 288 from the same rows; nothing gets all four.
                .jsonPath("$.imageSources[0].type")
                .isEqualTo("image/avif")
                .jsonPath("$.imageSources[0].srcset")
                .isEqualTo("/api/images/$HASH/768.avif 768w, /api/images/$HASH/1536.avif 1536w")
                .jsonPath("$.imageSources[2].type")
                .isEqualTo("image/jpeg")
        }

    @Test
    fun `the list response asks for the card width`(): Unit =
        runBlocking {
            val venueId = insertVenue("Lido", "lido")
            insertEvent(venueId, "Show", "show", LocalDate.now().plusDays(3), imageUrl = POSTER_URL)
            insertCachedImage(POSTER_URL, HASH, ALL_WIDTHS)

            webTestClient
                .get()
                .uri("/events")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[0].imageUrl")
                .isEqualTo("/api/images/$HASH/192.jpg")
                .jsonPath("$.content[0].imageSources[0].srcset")
                .isEqualTo("/api/images/$HASH/192.avif 192w, /api/images/$HASH/288.avif 288w")
        }

    @Test
    @DisplayName("a venue's own image is served from our origin, not the venue's")
    fun `the venue response carries our url`(): Unit =
        runBlocking {
            // The three columns #833 was raised about. None is scraped, so no `event_source` licence
            // reaches them, and nothing offered them to the fetcher until now.
            insertVenue("Lido", "lido", imageUrl = LOGO_URL)
            insertCachedImage(LOGO_URL, LOGO_HASH, ALL_WIDTHS)

            webTestClient
                .get()
                .uri("/venues/lido")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.imageUrl")
                .isEqualTo("/api/images/$LOGO_HASH/192.jpg")
                .jsonPath("$.imageSources[0].type")
                .isEqualTo("image/avif")
        }

    @Test
    fun `an artist photograph is served from our origin`(): Unit =
        runBlocking {
            insertArtist("Act", "act", imageUrl = LOGO_URL)
            insertCachedImage(LOGO_URL, LOGO_HASH, ALL_WIDTHS)

            webTestClient
                .get()
                .uri("/artists/act")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.imageUrl")
                .isEqualTo("/api/images/$LOGO_HASH/192.jpg")
        }

    @Test
    fun `a promoter logo is served from our origin, in the list as well as the detail`(): Unit =
        runBlocking {
            insertPromoter("Promo", "promo", imageUrl = LOGO_URL)
            insertCachedImage(LOGO_URL, LOGO_HASH, ALL_WIDTHS)

            webTestClient
                .get()
                .uri("/promoters")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[0].imageUrl")
                .isEqualTo("/api/images/$LOGO_HASH/192.jpg")
        }

    @Test
    @DisplayName("the venue embedded in an event response is rewritten too")
    fun `an embedded venue summary carries our url`(): Unit =
        runBlocking {
            // The one an earlier draft missed. Rewriting the event's image and leaving the venue
            // summary's beside it would hand out a venue URL from the endpoint that had just
            // stopped doing exactly that.
            val venueId = insertVenue("Lido", "lido", imageUrl = LOGO_URL)
            insertEvent(venueId, "Show", "show", LocalDate.now().plusDays(3), imageUrl = POSTER_URL)
            insertCachedImage(POSTER_URL, HASH, ALL_WIDTHS)
            insertCachedImage(LOGO_URL, LOGO_HASH, ALL_WIDTHS)

            webTestClient
                .get()
                .uri("/events/show")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.imageUrl")
                .isEqualTo("/api/images/$HASH/768.jpg")
                .jsonPath("$.venue.imageUrl")
                .isEqualTo("/api/images/$LOGO_HASH/192.jpg")
        }

    @Test
    @DisplayName("an image we do not hold is reported absent rather than hotlinked")
    fun `an uncached image url is blanked`(): Unit =
        runBlocking {
            val venueId = insertVenue("Lido", "lido")
            insertEvent(venueId, "Show", "show", LocalDate.now().plusDays(3), imageUrl = POSTER_URL)

            webTestClient
                .get()
                .uri("/events/show")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.imageUrl")
                .doesNotExist()
                .jsonPath("$.imageSources")
                .isEmpty()
        }

    @Test
    @DisplayName("a second request for the same object never reaches the bucket")
    fun `the object is served from memory once it has been read`(): Unit =
        runBlocking {
            val key = derivedKey(CACHED_HASH, 288, "jpg")
            insertCachedImage(CACHED_URL, CACHED_HASH, listOf(288))
            putObject(key, POSTER)

            webTestClient
                .get()
                .uri("/images/$CACHED_HASH/288.jpg")
                .exchange()
                .expectStatus()
                .isOk

            // Taking the object away is the assertion. A read-through answers 404 here, so the
            // test fails loudly. Counting `getObject` calls would only prove a number.
            deleteObject(key)

            webTestClient
                .get()
                .uri("/images/$CACHED_HASH/288.jpg")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .consumeWith { assertContentEquals(POSTER, it.responseBody) }
        }

    // What reserves the space a lazy image will take. `srcset` is what makes it necessary: without
    // dimensions the browser cannot know the shape until the bytes land, and the page reflows (#848).
    @Test
    fun `the response carries the intrinsic dimensions`(): Unit =
        runBlocking {
            insertCachedImage(POSTER_URL, HASH, ALL_WIDTHS, intrinsicWidth = 1200, intrinsicHeight = 630)
            val venueId = insertVenue("Lido", "lido")
            insertEvent(venueId, "Show", "show", LocalDate.now().plusDays(3), imageUrl = POSTER_URL)

            webTestClient
                .get()
                .uri("/events/show")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.intrinsicWidth")
                .isEqualTo(1200)
                .jsonPath("$.intrinsicHeight")
                .isEqualTo(630)
        }

    // 16% of staging's images had no dimensions at import, because a stock JVM reads neither WebP
    // nor AVIF. Reporting one of the pair would reserve nothing and look like it had worked.
    @Test
    @DisplayName("an image measured on only one axis reports neither")
    fun `a half-measured image reports no dimensions`(): Unit =
        runBlocking {
            insertCachedImage(LOGO_URL, LOGO_HASH, ALL_WIDTHS, intrinsicWidth = 1200)
            insertVenue("Astra", "astra", imageUrl = LOGO_URL)

            webTestClient
                .get()
                .uri("/venues/astra")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.intrinsicWidth")
                .doesNotExist()
                .jsonPath("$.intrinsicHeight")
                .doesNotExist()
        }

    private suspend fun deleteObject(key: String) {
        s3
            .deleteObject(
                DeleteObjectRequest
                    .builder()
                    .bucket(BUCKET)
                    .key(key)
                    .build()
            ).await()
    }

    private suspend fun putObject(
        key: String,
        bytes: ByteArray
    ) {
        s3
            .putObject(
                PutObjectRequest
                    .builder()
                    .bucket(BUCKET)
                    .key(key)
                    .contentType("image/jpeg")
                    .build(),
                AsyncRequestBody.fromBytes(bytes)
            ).await()
    }

    companion object {
        private const val BUCKET = "images"
        private const val POSTER_URL = "https://venue.test/poster.jpg"
        private const val HASH = "0f4b2c1d5e6a7b8c9d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e"

        private const val LOGO_URL = "https://venue.test/logo.jpg"
        private const val LOGO_HASH = "9e8d7c6b5a4938271605f4e3d2c1b0a99e8d7c6b5a4938271605f4e3d2c1b0a9"

        /** Its own hash, because the cache outlives one test method and a shared key would leak. */
        private const val CACHED_URL = "https://venue.test/cached.jpg"
        private const val CACHED_HASH = "5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c"

        /** Two source URLs on one hash, which is what makes `findStorageKey` return two rows. */
        private const val ALTERNATE_URL = "https://venue.test/poster-copy.jpg"
        private const val DUPLICATE_HASH = "3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f7081920"

        /** A row whose object was never put in the bucket. */
        private const val ORPHAN_HASH = "1a2b3c4d5e6f708192a3b4c5d6e7f8090f4b2c1d5e6a7b8c9d0e1f2a3b4c5d6e"

        /** The three endings the requests below produce, one each. `unavailable` needs a broken bucket. */
        private val OUTCOMES = listOf("found", "missing", "unknown")

        /** Not a real JPEG. Nothing here decodes it, and the assertion is byte equality. */
        private val POSTER = ByteArray(64) { it.toByte() }

        /** What imgproxy generates: 96 px cards at 2x and 3x, then the detail column at 1x and 2x. */
        private val ALL_WIDTHS = listOf(192, 288, 768, 1536)

        private val minio = MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z")

        private lateinit var s3: S3AsyncClient

        @JvmStatic
        @DynamicPropertySource
        fun imageProperties(registry: DynamicPropertyRegistry) {
            minio.start()
            s3 =
                S3AsyncClient
                    .builder()
                    .endpointOverride(URI(minio.s3URL))
                    .region(Region.of("fsn1"))
                    .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(minio.userName, minio.password))
                    ).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .build()
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build()).join()

            registry.add("app.images.serving.enabled") { true }
            registry.add("app.images.storage.endpoint") { minio.s3URL }
            registry.add("app.images.storage.bucket") { BUCKET }
            registry.add("app.images.storage.access-key") { minio.userName }
            registry.add("app.images.storage.secret-key") { minio.password }
        }
    }
}
