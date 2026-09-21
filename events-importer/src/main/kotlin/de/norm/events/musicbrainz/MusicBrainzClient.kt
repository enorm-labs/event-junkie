package de.norm.events.musicbrainz

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientException
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitExchange
import org.springframework.web.util.UriBuilder
import java.io.IOException
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** MusicBrainz did not answer, or answered 503. A counter and a retry on the next sweep, never a failed source. */
class MusicBrainzUnavailableException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * The artist search of MusicBrainz WS/2, at the pace the service asks for.
 *
 * `GET artist/?query=artist:"<name>"&fmt=json&limit=10`. **The trailing slash is not optional**:
 * the bare `artist?query=` path answers 503, indistinguishable from the rate limit (ADR-031).
 * [artist] reads one entity with its URL relationships, `GET artist/{mbid}?inc=url-rels&fmt=json`,
 * for step C.
 *
 * **One request at a time, [MusicBrainzProperties.politeDelayMillis] apart, process-wide.** The
 * limit is one a second per source address, shared by every process behind it, so two sweeps
 * running for two sources queue on the one mutex rather than each keeping a timer. A 503 is
 * retried [MusicBrainzProperties.retries] times behind a pause that triples each time, because
 * MusicBrainz's bursts outlast one pause (#1610); when they are spent, or on any transport
 * failure, [MusicBrainzUnavailableException] is the caller's to count.
 */
@Component
class MusicBrainzClient(
    @Qualifier(MUSICBRAINZ_WEB_CLIENT) private val webClient: WebClient,
    private val properties: MusicBrainzProperties
) {
    private val logger = KotlinLogging.logger {}
    private val pace = Mutex()
    private var lastRequest: TimeMark? = null

    /** The candidates MusicBrainz returns for [name], in its order; empty when it knows no such artist. */
    suspend fun search(name: String): List<MusicBrainzCandidate> {
        val query = "artist:\"${escapeLucene(name)}\""
        return unavailableOnTransportFailure(name) {
            fetchWithRetries("'$name'") {
                get(
                    uri = { builder ->
                        builder
                            .path("artist/")
                            .queryParam("query", "{query}")
                            .queryParam("fmt", "json")
                            .queryParam("limit", CANDIDATES)
                            .build(query)
                    },
                    body = { response -> response.awaitBody<MusicBrainzSearchResponse>().artists }
                )
            }.orEmpty()
        }
    }

    /**
     * The entity behind an EXACT verdict, with its URL relationships. Null on 404: the MBID was
     * deleted, and a merged one is followed to its survivor by the client's redirect handling.
     */
    suspend fun artist(mbid: String): MusicBrainzArtist? =
        unavailableOnTransportFailure(mbid) {
            fetchWithRetries("'$mbid'") {
                get(
                    uri = { builder ->
                        builder
                            .path("artist/{mbid}")
                            .queryParam("inc", "url-rels")
                            .queryParam("fmt", "json")
                            .build(mbid)
                    },
                    body = { response -> response.awaitBody<MusicBrainzArtist>() }
                )
            }
        }

    private suspend fun <T> unavailableOnTransportFailure(
        subject: String,
        block: suspend () -> T
    ): T =
        try {
            block()
        } catch (e: IOException) {
            throw MusicBrainzUnavailableException("MusicBrainz did not answer for '$subject'", e)
        } catch (e: WebClientException) {
            throw MusicBrainzUnavailableException("MusicBrainz request failed for '$subject'", e)
        }

    /**
     * Retries [fetch] behind growing pauses while it answers 503. [Fetched.Missing] and a body come
     * back as they are; only the spent retries throw.
     */
    private suspend fun <T> fetchWithRetries(
        subject: String,
        fetch: suspend () -> Fetched<T>
    ): T? {
        var pause = properties.backoff.toMillis()
        repeat(properties.retries) {
            val fetched = fetch()
            if (fetched !is Fetched.Unavailable) return fetched.value()
            logger.info { "MusicBrainz answered 503; backing off ${pause}ms" }
            delay(pause)
            pause *= BACKOFF_GROWTH
        }
        val last = fetch()
        if (last is Fetched.Unavailable) throw MusicBrainzUnavailableException("MusicBrainz answered 503 ${properties.retries + 1} times for $subject")
        return last.value()
    }

    /** One paced request. [Fetched.Unavailable] on 503, so the caller decides whether to try again. */
    private suspend fun <T> get(
        uri: (UriBuilder) -> URI,
        body: suspend (ClientResponse) -> T
    ): Fetched<T> =
        pace.withLock {
            waitForPace()
            webClient
                .get()
                .uri(uri)
                .awaitExchange { response ->
                    when {
                        response.statusCode() == HttpStatus.SERVICE_UNAVAILABLE -> Fetched.Unavailable
                        response.statusCode() == HttpStatus.NOT_FOUND -> Fetched.Missing
                        response.statusCode().isError -> throw MusicBrainzUnavailableException("MusicBrainz answered ${response.statusCode().value()}")
                        else -> Fetched.Body(body(response))
                    }
                }
        }

    /** The outcome of one request, before the retry policy decides what it means. */
    private sealed interface Fetched<out T> {
        fun value(): T?

        data class Body<T>(
            val body: T
        ) : Fetched<T> {
            override fun value(): T = body
        }

        /** 404: a search never answers it, an entity does when its MBID is gone. */
        data object Missing : Fetched<Nothing> {
            override fun value(): Nothing? = null
        }

        /** 503: the rate limit or an outage. */
        data object Unavailable : Fetched<Nothing> {
            override fun value(): Nothing? = null
        }
    }

    private suspend fun waitForPace() {
        val since = lastRequest?.elapsedNow()
        val gap = properties.politeDelayMillis.milliseconds
        if (since != null && since < gap) delay(gap - since)
        lastRequest = TimeSource.Monotonic.markNow()
    }

    companion object {
        private const val CANDIDATES = 10
        private const val BACKOFF_GROWTH = 3

        /** A backslash and a double quote are the two characters that end a Lucene phrase early. */
        fun escapeLucene(name: String): String = name.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
