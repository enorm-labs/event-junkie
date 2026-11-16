package de.norm.events.musicbrainz

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import de.norm.events.common.rethrowIfCancelled
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.netty.channel.ChannelOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.Locale
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.toKotlinDuration

private val logger = KotlinLogging.logger {}

/** MusicBrainz did not answer, or answered 503: its rate limit or an outage. The caller answers 503. */
class MusicBrainzUnavailableException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/** One artist as MusicBrainz returns it, reduced to what a visitor needs to tell namesakes apart. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MusicBrainzArtist(
    val id: String,
    val name: String,
    val type: String? = null,
    val country: String? = null,
    val disambiguation: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class MusicBrainzSearchResponse(
    val artists: List<MusicBrainzArtist> = emptyList()
)

/**
 * The artist search of MusicBrainz WS/2 for a visitor who watches an act that has no artist row yet (#5214).
 *
 * MusicBrainz stays a source, not a processor (LEGAL.md §7), because of three rules this class keeps:
 * - The request carries the term, `fmt`, `limit` and the `User-Agent`. Nothing of the visitor is forwarded.
 * - The term is never logged. The line carries its length, the candidate count and the outcome.
 * - Answers live [cacheFor] in memory, never on disk.
 *
 * **One request at a time, [pace] apart, per process.** MusicBrainz allows one a second per source address. The importer
 * keeps the same pace, and the two can leave the cluster through one address, so a burst here can cost the importer's
 * sweep a 503, which it retries (#1610). No retry here: a visitor gets a 503 at once rather than a wait.
 *
 * `GET artist/?query=…`: **the trailing slash is not optional**, the bare path answers 503 (ADR-031). The query is a
 * phrase on the name and the aliases, as the importer's lookup is. A partial word finds nothing.
 * `GET artist/{mbid}` follows the redirect a merged MBID answers with, so [artist] returns the survivor's id.
 * Only name, type, country and disambiguation are mapped. A person's life span is a birth date, and tags are
 * CC BY-NC-SA (ADR-031).
 */
@Component
class MusicBrainzSearch(
    webClients: WebClient.Builder,
    private val registry: MeterRegistry,
    @Value("\${app.musicbrainz.url}") baseUrl: String,
    @Value("\${app.musicbrainz.pace}") pace: Duration,
    @Value("\${app.musicbrainz.timeout}") private val timeout: Duration,
    @Value("\${app.musicbrainz.cache-for}") cacheFor: Duration
) {
    private val client =
        webClients
            .clone()
            .baseUrl(baseUrl)
            .defaultHeader("User-Agent", USER_AGENT)
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient
                        .create()
                        .followRedirect(true)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout.toMillis().toInt())
                        .responseTimeout(timeout)
                )
            ).build()
    private val gap = pace.toKotlinDuration()
    private val mutex = Mutex()
    private var lastRequest: TimeMark? = null
    private val cache: Cache<String, List<MusicBrainzArtist>> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(cacheFor)
            .maximumSize(CACHE_SIZE)
            .build()

    /** The artists MusicBrainz finds for [term], in its order. A cached answer is served without a request. */
    suspend fun search(term: String): List<MusicBrainzArtist> {
        val key = key(term)
        cache.getIfPresent(key)?.let {
            record(SEARCH, Outcome.CACHED)
            return it
        }
        val found =
            fetch(SEARCH, "a term of ${term.length} characters") {
                client
                    .get()
                    .uri { builder ->
                        builder
                            .path("artist/")
                            .queryParam("query", "{query}")
                            .queryParam("fmt", "json")
                            .queryParam("limit", CANDIDATES)
                            .build(query(term))
                    }.retrieve()
                    .bodyToMono(MusicBrainzSearchResponse::class.java)
                    .awaitSingle()
                    .artists
            }.orEmpty()
        cache.put(key, found)
        return found
    }

    /** The artist behind [mbid], or the one it was merged into. Null when MusicBrainz knows no such id. */
    suspend fun artist(mbid: String): MusicBrainzArtist? =
        fetch(ARTIST, "one id") {
            client
                .get()
                .uri { builder -> builder.path("artist/{mbid}").queryParam("fmt", "json").build(mbid) }
                .retrieve()
                .bodyToMono(MusicBrainzArtist::class.java)
                .awaitSingle()
        }

    /**
     * One paced request. A 404 is null. A 503, any other error status and a transport failure are [MusicBrainzUnavailableException]:
     * every failure of the call out is one outcome for the caller. A cancellation is rethrown.
     */
    @Suppress("TooGenericExceptionCaught", "SuspendFunSwallowedCancellation")
    private suspend fun <T> fetch(
        what: String,
        subject: String,
        request: suspend () -> T
    ): T? {
        val started = System.nanoTime()
        val failure =
            try {
                return paced(request).also { log(what, "$subject: ${Outcome.OK.tag}${count(it)}", Outcome.OK, started) }
            } catch (e: WebClientResponseException) {
                if (e.statusCode == HttpStatus.NOT_FOUND) {
                    log(what, "$subject: ${Outcome.NOT_FOUND.tag}", Outcome.NOT_FOUND, started)
                    return null
                }
                log(what, "$subject: answered ${e.statusCode.value()}", Outcome.UNAVAILABLE, started)
                e
            } catch (e: Exception) {
                rethrowIfCancelled()
                logger.warn(e) { "MusicBrainz $what for $subject failed" }
                record(what, Outcome.UNAVAILABLE)
                e
            }
        throw MusicBrainzUnavailableException("MusicBrainz did not answer", failure)
    }

    private fun count(result: Any?): String = (result as? List<*>)?.let { ", ${it.size} candidates" }.orEmpty()

    private suspend fun <T> paced(request: suspend () -> T): T =
        mutex.withLock {
            val since = lastRequest?.elapsedNow()
            if (since != null && since < gap) delay(gap - since)
            lastRequest = TimeSource.Monotonic.markNow()
            request()
        }

    /** One line per call out. [summary] names the subject and the outcome, never the term. */
    private fun log(
        what: String,
        summary: String,
        outcome: Outcome,
        started: Long
    ) {
        record(what, outcome)
        val ms = (System.nanoTime() - started) / NANOS_PER_MILLI
        val line = "MusicBrainz $what for $summary (${ms}ms)"
        if (outcome == Outcome.UNAVAILABLE) logger.warn { line } else logger.info { line }
    }

    private fun record(
        what: String,
        outcome: Outcome
    ) = registry.counter(METRIC, TAG_REQUEST, what, TAG_OUTCOME, outcome.tag).increment()

    private enum class Outcome(
        val tag: String
    ) {
        OK("ok"),
        CACHED("cached"),
        NOT_FOUND("not_found"),
        UNAVAILABLE("unavailable")
    }

    companion object {
        /** `bff.musicbrainz.requests{request,outcome}`: searches and id reads for watches (#5214), cached answers included. */
        const val METRIC = "bff.musicbrainz.requests"
        private const val TAG_REQUEST = "request"
        private const val TAG_OUTCOME = "outcome"
        private const val SEARCH = "search"
        private const val ARTIST = "artist"
        private const val CANDIDATES = 10
        private const val CACHE_SIZE = 5_000L
        private const val NANOS_PER_MILLI = 1_000_000
        const val USER_AGENT = "EventJunkie/1.0 (+https://github.com/enorm-labs/event-junkie)"

        /** The cache key: case and runs of space do not make a second request. */
        fun key(term: String): String = term.trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT)

        /** A phrase on the name or an alias. A backslash and a double quote are the two characters that end a Lucene phrase early. */
        fun query(term: String): String {
            val phrase = key(term).replace("\\", "\\\\").replace("\"", "\\\"")
            return "artist:\"$phrase\" OR alias:\"$phrase\""
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
