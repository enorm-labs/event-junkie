package de.norm.events.discogs

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
import org.springframework.web.reactive.function.client.awaitExchange
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Discogs did not answer, or kept answering 429. A counter and a retry on the next sweep, never a failed source. */
class DiscogsUnavailableException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * The artist search of the Discogs database, at the pace its limit allows (#2026).
 *
 * `GET database/search?q=<name>&type=artist&per_page=25`. **One request at a time,
 * [DiscogsProperties.politeDelayMillis] apart, process-wide**: the limit is 60 a minute per source
 * address in a rolling window. The pause alone does not hold it — the spike's first run spent most
 * of six hours in 429 sleeps (#1627) — so the client also reads `X-Discogs-Ratelimit-Remaining` and
 * waits [DiscogsProperties.backoff] when the window is nearly spent. A 429 is retried behind the
 * same pause; when the retries are spent, or on any transport failure or other error status,
 * [DiscogsUnavailableException] is the caller's to count.
 *
 * **No message here carries a credential.** The header is set once in [DiscogsHttpClientConfig],
 * and the messages name the searched name and the status only.
 */
@Component
class DiscogsClient(
    @Qualifier(DISCOGS_WEB_CLIENT) private val webClient: WebClient,
    private val properties: DiscogsProperties
) {
    private val logger = KotlinLogging.logger {}
    private val pace = Mutex()
    private var lastRequest: TimeMark? = null

    /** The candidates Discogs returns for [name], in its order; empty when it knows no such artist. */
    suspend fun search(name: String): List<DiscogsCandidate> =
        try {
            fetchWithRetries(name)
        } catch (e: IOException) {
            throw DiscogsUnavailableException("Discogs did not answer for '$name'", e)
        } catch (e: WebClientException) {
            throw DiscogsUnavailableException("Discogs request failed for '$name'", e)
        }

    private suspend fun fetchWithRetries(name: String): List<DiscogsCandidate> {
        repeat(properties.retries + 1) {
            val results = get(name)
            if (results != null) return results
            logger.info { "Discogs answered 429; backing off ${properties.backoff.toMillis()}ms" }
            delay(properties.backoff.toMillis())
        }
        throw DiscogsUnavailableException("Discogs answered 429 ${properties.retries + 1} times for '$name'")
    }

    /** One paced request. Null on 429, so the caller decides whether to try again. */
    private suspend fun get(name: String): List<DiscogsCandidate>? =
        pace.withLock {
            waitForPace()
            val (results, remaining) =
                webClient
                    .get()
                    .uri { builder ->
                        builder
                            .path("database/search")
                            .queryParam("q", "{q}")
                            .queryParam("type", "artist")
                            .queryParam("per_page", CANDIDATES)
                            .build(name)
                    }.awaitExchange { response ->
                        val remaining =
                            response
                                .headers()
                                .header(RATE_LIMIT_REMAINING)
                                .firstOrNull()
                                ?.toIntOrNull()
                        when {
                            response.statusCode() == HttpStatus.TOO_MANY_REQUESTS -> {
                                null to remaining
                            }

                            response.statusCode().isError -> {
                                throw DiscogsUnavailableException("Discogs answered ${response.statusCode().value()} for '$name'")
                            }

                            else -> {
                                response.awaitBody<DiscogsSearchResponse>().results to remaining
                            }
                        }
                    }
            if (remaining != null && remaining <= NEAR_LIMIT_REMAINING) {
                logger.debug { "Discogs window nearly spent ($remaining left); pausing ${properties.backoff.toMillis()}ms" }
                delay(properties.backoff.toMillis())
            }
            results
        }

    private suspend fun waitForPace() {
        val since = lastRequest?.elapsedNow()
        val gap = properties.politeDelayMillis.milliseconds
        if (since != null && since < gap) delay(gap - since)
        lastRequest = TimeSource.Monotonic.markNow()
    }

    private companion object {
        const val CANDIDATES = 25
        const val NEAR_LIMIT_REMAINING = 2
        const val RATE_LIMIT_REMAINING = "X-Discogs-Ratelimit-Remaining"
    }
}
