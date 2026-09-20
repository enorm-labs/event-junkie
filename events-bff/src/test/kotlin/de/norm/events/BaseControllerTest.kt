package de.norm.events

import de.norm.events.common.ResponseCache
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
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
 * Base class for BFF controller integration tests: a running server over a Testcontainers
 * PostgreSQL provisioned by the importer's Flyway migrations, a [WebTestClient], a [BeforeEach]
 * that truncates all tables, and raw-SQL seed helpers (the BFF's read entities omit required
 * write-only columns such as `event.source_id`).
 *
 * `@AutoConfigureMetrics` is here rather than on the tests that need it (#965): Spring Boot
 * forces `management.defaults.metrics.export.enabled` false in tests, and a test carrying the
 * annotation itself forks a second cached context, with its own container and pool. On the base
 * it costs nothing measurable; production is unaffected.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
@Import(PostgresTestcontainersConfiguration::class)
@Suppress("AbstractClassCanBeConcreteClass") // The shared setup for the suites below it; an instance of it alone tests nothing.
abstract class BaseControllerTest {
    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var databaseClient: DatabaseClient

    @Autowired
    protected lateinit var responseCache: ResponseCache

    /**
     * The client every controller test issues requests through. `responseTimeout` is a crash guard,
     * not a performance assertion (#504): unset, `WebTestClient` uses 5 seconds, the only bound on a
     * hung request in this repository. Five is too tight: the first request in a `@SpringBootTest`
     * class pays for a fresh context and a Testcontainers PostgreSQL, 1.3 s on an idle laptop, and
     * `ArtistControllerTest` timed out on exactly that path on a loaded runner. Thirty seconds is
     * 600× the steady state and 20× the cold start; exceeding it means something is broken.
     *
     * Mirrored in the other module's `BaseControllerTest`: change both or neither, or the suite is
     * flaky in one module for a reason nobody would think to compare.
     */
    protected val webTestClient: WebTestClient by lazy { clientAt("/api") }

    /**
     * The same client without the API prefix, for the actuator: deployed it answers on its own
     * management port, locally it shares this one, so a health path that moved with the API would
     * be a second divergence (#857).
     */
    protected val rootClient: WebTestClient by lazy { clientAt("") }

    private fun clientAt(prefix: String): WebTestClient =
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port$prefix")
            .responseTimeout(RESPONSE_TIMEOUT)
            .build()

    private companion object {
        val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(30)
    }

    /**
     * Truncates all domain tables before each test. The response cache is emptied with them (#269):
     * keyed on the request, a row deleted by TRUNCATE stays visible for the TTL. A zero TTL in the
     * test configuration would leave the caching path unexercised.
     */
    @BeforeEach
    fun cleanUp() =
        runBlocking {
            responseCache.clear()
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
                "INSERT INTO events.venue " +
                    "(name, slug, city, address, image_url, image_attribution, image_licence_id, image_source_url, district, description) " +
                    "VALUES (:name, :slug, :city, :address, :imageUrl, :attribution, :licenceId, :sourceUrl, :district, :description) RETURNING id"
            ).bind("name", name)
            .bind("slug", slug)
            .bind("city", city)
            .bindOrNull("address", address)
            .bindOrNull("imageUrl", imageUrl)
            .bindCredit(imageUrl)
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
                "INSERT INTO events.artist (name, slug, image_url, image_attribution, image_licence_id, image_source_url, description) " +
                    "VALUES (:name, :slug, :imageUrl, :attribution, :licenceId, :sourceUrl, :description) RETURNING id"
            ).bind("name", name)
            .bind("slug", slug)
            .bindOrNull("imageUrl", imageUrl)
            .bindCredit(imageUrl)
            .bindOrNull("description", description)
            .mapId()

    protected suspend fun insertPromoter(
        name: String,
        slug: String,
        imageUrl: String? = null,
        description: String? = null,
        descriptionLanguage: String? = null,
        descriptionAlt: String? = null,
        descriptionAltLanguage: String? = null
    ): Long =
        databaseClient
            .sql(
                "INSERT INTO events.promoter (name, slug, image_url, image_attribution, image_licence_id, image_source_url, " +
                    "description, description_language, description_alt, description_alt_language) " +
                    "VALUES (:name, :slug, :imageUrl, :attribution, :licenceId, :sourceUrl, " +
                    ":description, :descriptionLanguage, :descriptionAlt, :descriptionAltLanguage) RETURNING id"
            ).bind("name", name)
            .bind("slug", slug)
            .bindOrNull("imageUrl", imageUrl)
            .bindCredit(imageUrl)
            .bindOrNull("description", description)
            .bindOrNull("descriptionLanguage", descriptionLanguage)
            .bindOrNull("descriptionAlt", descriptionAlt)
            .bindOrNull("descriptionAltLanguage", descriptionAltLanguage)
            .mapId()

    protected suspend fun insertGenreTag(
        name: String,
        slug: String,
        family: String? = null
    ): Long =
        databaseClient
            .sql("INSERT INTO events.genre_tag (name, slug, family) VALUES (:name, :slug, :family) RETURNING id")
            .bind("name", name)
            .bind("slug", slug)
            .bindOrNull("family", family)
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
        doorsTime: LocalTime? = null,
        endDate: LocalDate? = null,
        endTime: LocalTime? = null,
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
                    "(venue_id, title, subtitle, slug, event_date, start_time, doors_time, end_date, end_time, source_id, " +
                    "event_type, price_presale, price_box_office, genre, sold_out, free, image_url) " +
                    "VALUES (:venueId, :title, :subtitle, :slug, :eventDate, :startTime, :doorsTime, :endDate, :endTime, " +
                    ":sourceId, :eventType, :pricePresale, :priceBoxOffice, :genre, :soldOut, :free, :imageUrl) " +
                    "RETURNING id"
            ).bind("venueId", venueId)
            .bind("title", title)
            .bindOrNull("subtitle", subtitle)
            .bind("slug", slug)
            .bind("eventDate", eventDate)
            .bindOrNull("startTime", startTime, LocalTime::class.java)
            .bindOrNull("doorsTime", doorsTime, LocalTime::class.java)
            .bindOrNull("endDate", endDate, LocalDate::class.java)
            .bindOrNull("endTime", endTime, LocalTime::class.java)
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
     * have written them (ADR-019). Keys come from [derivedKey]; no environment prefix, because a
     * served key is read from the row.
     */
    protected suspend fun insertCachedImage(
        sourceUrl: String,
        contentHash: String,
        widths: List<Int>,
        formats: List<String> = listOf("avif", "webp", "jpg"),
        deleted: Boolean = false,
        // Null by default: a stock JVM measures neither WebP nor AVIF, so 16% of the real rows carry no
        // dimensions (#848).
        intrinsicWidth: Int? = null,
        intrinsicHeight: Int? = null
    ): Long {
        val imageId =
            databaseClient
                .sql(
                    "INSERT INTO events.cached_image " +
                        "(source_url, content_hash, content_type, intrinsic_width, intrinsic_height, fetched_at, deleted_at) " +
                        "VALUES (:sourceUrl, :contentHash, 'image/jpeg', :intrinsicWidth, :intrinsicHeight, NOW(), " +
                        (if (deleted) "NOW()" else "NULL") + ") RETURNING id"
                ).bind("sourceUrl", sourceUrl)
                .bind("contentHash", contentHash)
                .bindOrNull("intrinsicWidth", intrinsicWidth, Int::class.java)
                .bindOrNull("intrinsicHeight", intrinsicHeight, Int::class.java)
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

    /**
     * The credit every image row owes, or nulls where the fixture has no image: V020 refuses a row
     * with an `image_url` and no attribution.
     */
    private fun DatabaseClient.GenericExecuteSpec.bindCredit(imageUrl: String?): DatabaseClient.GenericExecuteSpec =
        bindOrNull("attribution", imageUrl?.let { "Fixture Photographer" })
            .bindOrNull("licenceId", imageUrl?.let { "CC-BY-SA-4.0" })
            .bindOrNull("sourceUrl", imageUrl?.let { "https://commons.wikimedia.org/wiki/File:Fixture.jpg" })

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
