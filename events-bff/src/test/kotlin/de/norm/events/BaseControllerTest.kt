package de.norm.events

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.test.web.reactive.server.WebTestClient
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * Base class for BFF controller integration tests.
 *
 * Provides a running server backed by a Testcontainers PostgreSQL database (schema provisioned
 * by the importer's Flyway migrations), a pre-configured [WebTestClient], a [BeforeEach] hook
 * that truncates all tables, and raw-SQL seed helpers. Seeding uses SQL rather than the BFF's
 * lean read entities because those intentionally omit required write-only columns (e.g.
 * `event.source_id`).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainersConfiguration::class)
abstract class BaseControllerTest {
    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var databaseClient: DatabaseClient

    /**
     * The client every controller test issues requests through.
     *
     * **`responseTimeout` is set deliberately, and 30 seconds is a crash guard rather than a
     * performance assertion (#504.)** Left unset, `WebTestClient` uses Spring's documented default
     * of **5 seconds**, and nothing in this repository had chosen that number. It is the only bound
     * on a hung request — there is no JUnit platform timeout and no timeout on the Gradle `Test`
     * task — so removing it entirely would turn a deadlock into a stalled build.
     *
     * Five seconds is too tight for what it guards. These endpoints answer in about 50 ms once warm,
     * but the **first** request in a `@SpringBootTest` class pays for a freshly started context and a
     * Testcontainers PostgreSQL: measured at 1.3 s on an idle laptop, and a loaded CI runner
     * multiplies that. `ArtistControllerTest` timed out on exactly that path — its own duplicate-name
     * test runs in 48 ms, one of the fastest in the class, so nothing was slow except the runner.
     *
     * Thirty seconds is roughly 600× the steady-state cost and 20× the cold start. Exceeding it means
     * something is genuinely broken, which is the only thing a test-suite timeout should ever claim.
     *
     * **MIRRORED IN THE OTHER MODULE'S `BaseControllerTest` — change both or neither.** The two files
     * are deliberate twins, like the per-cluster cert-manager manifests: a value that differs between
     * them would produce a suite that is flaky in one module and not the other, for a reason nobody
     * would think to compare.
     */
    protected val webTestClient: WebTestClient by lazy {
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port")
            .responseTimeout(RESPONSE_TIMEOUT)
            .build()
    }

    private companion object {
        val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(30)
    }

    /** Truncates all domain tables before each test to ensure a clean state. */
    @BeforeEach
    fun cleanUp() =
        runBlocking {
            databaseClient
                .sql(
                    "TRUNCATE TABLE events.cached_image_variant, events.cached_image, events.event_source, " +
                        "events.event_genre_tag, events.event_promoter, events.event_artist, " +
                        "events.event, events.genre_tag, events.promoter, events.artist, events.venue CASCADE"
                ).await()
        }

    protected suspend fun insertVenue(
        name: String,
        slug: String,
        city: String = "Berlin",
        address: String? = null,
        imageUrl: String? = null,
        district: String? = null,
        description: String? = null
    ): Long =
        databaseClient
            .sql(
                "INSERT INTO events.venue (name, slug, city, address, image_url, district, description) " +
                    "VALUES (:name, :slug, :city, :address, :imageUrl, :district, :description) RETURNING id"
            ).bind("name", name)
            .bind("slug", slug)
            .bind("city", city)
            .bindOrNull("address", address)
            .bindOrNull("imageUrl", imageUrl)
            .bindOrNull("district", district)
            .bindOrNull("description", description)
            .mapId()

    protected suspend fun insertArtist(
        name: String,
        slug: String,
        imageUrl: String? = null,
        description: String? = null
    ): Long =
        databaseClient
            .sql(
                "INSERT INTO events.artist (name, slug, image_url, description) " +
                    "VALUES (:name, :slug, :imageUrl, :description) RETURNING id"
            ).bind("name", name)
            .bind("slug", slug)
            .bindOrNull("imageUrl", imageUrl)
            .bindOrNull("description", description)
            .mapId()

    protected suspend fun insertPromoter(
        name: String,
        slug: String,
        imageUrl: String? = null
    ): Long =
        databaseClient
            .sql("INSERT INTO events.promoter (name, slug, image_url) VALUES (:name, :slug, :imageUrl) RETURNING id")
            .bind("name", name)
            .bind("slug", slug)
            .bindOrNull("imageUrl", imageUrl)
            .mapId()

    protected suspend fun insertGenreTag(
        name: String,
        slug: String
    ): Long =
        databaseClient
            .sql("INSERT INTO events.genre_tag (name, slug) VALUES (:name, :slug) RETURNING id")
            .bind("name", name)
            .bind("slug", slug)
            .mapId()

    @Suppress("LongParameterList")
    protected suspend fun insertEvent(
        venueId: Long,
        title: String,
        slug: String,
        eventDate: LocalDate,
        sourceId: String = "test:$slug",
        subtitle: String? = null,
        eventType: String = "CONCERT",
        startTime: LocalTime? = null,
        pricePresale: BigDecimal? = null,
        priceBoxOffice: BigDecimal? = null,
        genre: String? = null,
        soldOut: Boolean = false,
        free: Boolean = false,
        imageUrl: String? = null
    ): Long =
        databaseClient
            .sql(
                "INSERT INTO events.event " +
                    "(venue_id, title, subtitle, slug, event_date, start_time, source_id, event_type, " +
                    "price_presale, price_box_office, genre, sold_out, free, image_url) " +
                    "VALUES (:venueId, :title, :subtitle, :slug, :eventDate, :startTime, :sourceId, :eventType, " +
                    ":pricePresale, :priceBoxOffice, :genre, :soldOut, :free, :imageUrl) " +
                    "RETURNING id"
            ).bind("venueId", venueId)
            .bind("title", title)
            .bindOrNull("subtitle", subtitle)
            .bind("slug", slug)
            .bind("eventDate", eventDate)
            .bindOrNull("startTime", startTime, LocalTime::class.java)
            .bind("sourceId", sourceId)
            .bind("eventType", eventType)
            .bindOrNull("pricePresale", pricePresale, BigDecimal::class.java)
            .bindOrNull("priceBoxOffice", priceBoxOffice, BigDecimal::class.java)
            .bindOrNull("genre", genre)
            .bind("soldOut", soldOut)
            .bind("free", free)
            .bindOrNull("imageUrl", imageUrl)
            .mapId()

    /**
     * Seeds one cached venue image and one derivative per width and format, as the importer would
     * have written them (ADR-019).
     *
     * Keys come from [derivedKey], so a caller can put the matching object in a bucket. The real
     * keys carry an environment prefix; nothing on this side builds one, because a served key is
     * read from the row.
     */
    protected suspend fun insertCachedImage(
        sourceUrl: String,
        contentHash: String,
        widths: List<Int>,
        formats: List<String> = listOf("avif", "webp", "jpg"),
        deleted: Boolean = false
    ): Long {
        val imageId =
            databaseClient
                .sql(
                    "INSERT INTO events.cached_image (source_url, content_hash, content_type, fetched_at, deleted_at) " +
                        "VALUES (:sourceUrl, :contentHash, 'image/jpeg', NOW(), " +
                        (if (deleted) "NOW()" else "NULL") + ") RETURNING id"
                ).bind("sourceUrl", sourceUrl)
                .bind("contentHash", contentHash)
                .mapId()

        widths.forEach { width ->
            formats.forEach { format ->
                databaseClient
                    .sql(
                        "INSERT INTO events.cached_image_variant (cached_image_id, width, format, storage_key, byte_size) " +
                            "VALUES (:imageId, :width, :format, :storageKey, 1)"
                    ).bind("imageId", imageId)
                    .bind("width", width)
                    .bind("format", format)
                    .bind("storageKey", derivedKey(contentHash, width, format))
                    .await()
            }
        }
        return imageId
    }

    /** Where [insertCachedImage] claims a derivative lives. */
    protected fun derivedKey(
        contentHash: String,
        width: Int,
        format: String
    ): String = "test/derived/$contentHash/$width.$format"

    protected suspend fun linkArtist(
        eventId: Long,
        artistId: Long,
        role: String = "HEADLINER",
        billingOrder: Int = 0
    ) {
        databaseClient
            .sql(
                "INSERT INTO events.event_artist (event_id, artist_id, role, billing_order) " +
                    "VALUES (:eventId, :artistId, :role, :billingOrder)"
            ).bind("eventId", eventId)
            .bind("artistId", artistId)
            .bind("role", role)
            .bind("billingOrder", billingOrder)
            .await()
    }

    protected suspend fun linkPromoter(
        eventId: Long,
        promoterId: Long
    ) {
        databaseClient
            .sql("INSERT INTO events.event_promoter (event_id, promoter_id) VALUES (:eventId, :promoterId)")
            .bind("eventId", eventId)
            .bind("promoterId", promoterId)
            .await()
    }

    protected suspend fun linkGenre(
        eventId: Long,
        genreTagId: Long
    ) {
        databaseClient
            .sql("INSERT INTO events.event_genre_tag (event_id, genre_tag_id) VALUES (:eventId, :genreTagId)")
            .bind("eventId", eventId)
            .bind("genreTagId", genreTagId)
            .await()
    }

    private suspend fun DatabaseClient.GenericExecuteSpec.mapId(): Long = map { row: Readable -> row.get(0, Long::class.javaObjectType)!! }.one().awaitSingle()

    private fun DatabaseClient.GenericExecuteSpec.bindOrNull(
        name: String,
        value: String?
    ): DatabaseClient.GenericExecuteSpec = if (value != null) bind(name, value) else bindNull(name, String::class.java)

    private fun DatabaseClient.GenericExecuteSpec.bindOrNull(
        name: String,
        value: Any?,
        type: Class<*>
    ): DatabaseClient.GenericExecuteSpec = if (value != null) bind(name, value) else bindNull(name, type)
}
