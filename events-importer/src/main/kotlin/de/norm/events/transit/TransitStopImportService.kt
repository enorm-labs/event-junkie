package de.norm.events.transit

import de.norm.events.common.ApiUserAgent
import de.norm.events.common.rethrowIfCancelled
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize

/** What one import stored, for its closing log line and the tests. */
data class TransitImportSummary(
    val stations: Int,
    val bytes: Long
)

/**
 * One import of the U-Bahn, S-Bahn and tram stations from VBB's GTFS feed (#2724). The feed is downloaded to a
 * temporary file, because it is too large to hold in memory, read by [GtfsStopReader], and stored in place of the
 * last import. The request goes from the server; no visitor's browser reaches VBB.
 *
 * A feed with fewer than [TransitProperties.minStations] stations keeps the stored ones, and the pass logs ERROR.
 */
@Service
class TransitStopImportService(
    private val store: TransitStopStore,
    private val properties: TransitProperties,
    buildProperties: ObjectProvider<BuildProperties>,
    @Qualifier("ioDispatcher") private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = KotlinLogging.logger {}

    private val userAgent = ApiUserAgent.of(buildProperties.ifAvailable?.version)

    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build()

    /** Held while an import runs, so the schedule and the admin trigger never run two at once. */
    private val running = Mutex()

    /** Runs one import, or returns `null` when one is already running or this one failed. */
    @Suppress("TooGenericExceptionCaught", "SuspendFunSwallowedCancellation") // A pass that fails is logged here; the callers only start it.
    suspend fun runOnce(): TransitImportSummary? {
        if (!running.tryLock()) {
            logger.info { "Transit stop import skipped: an import is already running" }
            return null
        }
        return try {
            pass()
        } catch (e: Exception) {
            rethrowIfCancelled()
            logger.error(e) { "Transit stop import from ${properties.gtfsUrl} failed; the stored stations stay" }
            null
        } finally {
            running.unlock()
        }
    }

    private suspend fun pass(): TransitImportSummary {
        logger.info { "Transit stop import started from ${properties.gtfsUrl}" }
        val file = withContext(ioDispatcher) { Files.createTempFile("gtfs-", ".zip") }
        try {
            download(file)
            val bytes = withContext(ioDispatcher) { file.fileSize() }
            val stations = withContext(ioDispatcher) { GtfsStopReader.read(file) }
            check(stations.size >= properties.minStations) {
                "The feed names ${stations.size} station(s) with a U-Bahn, S-Bahn or tram line, fewer than ${properties.minStations}"
            }
            store.replaceAll(stations, Instant.now(clock))
            logger.info { "Transit stop import finished: ${stations.size} station(s) stored from $bytes bytes" }
            return TransitImportSummary(stations.size, bytes)
        } finally {
            // Not suspending, so a cancelled pass still removes the file; one unlink is too short to need the IO pool.
            file.deleteIfExists()
        }
    }

    private suspend fun download(file: Path) {
        val request =
            HttpRequest
                .newBuilder(URI(properties.gtfsUrl))
                .timeout(properties.downloadTimeout)
                .header("User-Agent", userAgent)
                .GET()
                .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofFile(file)).await()
        if (response.statusCode() != HTTP_OK) throw IOException("GTFS download answered ${response.statusCode()}")
    }

    companion object {
        private const val HTTP_OK = 200
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
