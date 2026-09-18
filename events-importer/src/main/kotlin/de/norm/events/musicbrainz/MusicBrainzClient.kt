package de.norm.events.musicbrainz

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientException
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitExchangeOrNull
import java.io.IOException
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
 *
 * **One request at a time, [MusicBrainzProperties.politeDelayMillis] apart, process-wide.** The
 * limit is one a second per source address, shared by every process behind it, so two sweeps
 * running for two sources queue on the one mutex rather than each keeping a timer. A 503 is
 * retried once after a longer pause; a second one, or any transport failure, is
 * [MusicBrainzUnavailableException] for the caller to count and stop on.
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
        return try {
            fetchWithOneRetry(query, name)
        } catch (e: IOException) {
            throw MusicBrainzUnavailableException("MusicBrainz did not answer for '$name'", e)
        } catch (e: WebClientException) {
            throw MusicBrainzUnavailableException("MusicBrainz request failed for '$name'", e)
        }
    }

    /** A 503 is the rate limit or an outage; one pause and one more try tells which. */
    private suspend fun fetchWithOneRetry(
        query: String,
        name: String
    ): List<MusicBrainzCandidate> =
        fetch(query) ?: run {
            delay(properties.backoff.toMillis())
            fetch(query) ?: throw MusicBrainzUnavailableException("MusicBrainz answered 503 twice for '$name'")
        }

    /** One paced request. Null on 503, so the caller decides whether to try again. */
    private suspend fun fetch(query: String): List<MusicBrainzCandidate>? =
        pace.withLock {
            waitForPace()
            webClient
                .get()
                .uri { builder ->
                    builder
                        .path("artist/")
                        .queryParam("query", "{query}")
                        .queryParam("fmt", "json")
                        .queryParam("limit", CANDIDATES)
                        .build(query)
                }.awaitExchangeOrNull { response ->
                    when {
                        response.statusCode() == HttpStatus.SERVICE_UNAVAILABLE -> {
                            logger.info { "MusicBrainz answered 503; backing off ${properties.backoff.toMillis()}ms" }
                            null
                        }

                        response.statusCode().isError -> {
                            throw MusicBrainzUnavailableException("MusicBrainz answered ${response.statusCode().value()}")
                        }

                        else -> {
                            response.awaitBody<MusicBrainzSearchResponse>().artists
                        }
                    }
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

        /** A backslash and a double quote are the two characters that end a Lucene phrase early. */
        fun escapeLucene(name: String): String = name.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
