package de.norm.events.image

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.norm.events.LogContextConfiguration
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.logging.logback.StructuredLogEncoder
import org.springframework.core.env.Environment
import org.springframework.mock.env.MockEnvironment
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture

/**
 * What the two storage warnings carry, asserted on the ECS JSON rather than the log event (#980):
 * a value welded into the sentence reads like one beside it, and only the top level of the
 * serialised object separates a filterable field from prose. A fake client rather than MinIO:
 * both assertions are about a call that failed.
 */
class ImageObjectReaderTest {
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var root: Logger

    @BeforeEach
    fun attachAppender() {
        root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        root.detachAppender(appender)
        appender.stop()
    }

    @Test
    @DisplayName("a missing object names the key as a field, not in the sentence")
    fun `writes the storage key beside the message when the object is gone`() =
        runTest {
            val outcome = readerFor(NoSuchKeyException.builder().build()).read(KEY)

            outcome.shouldBeInstanceOf<ImageObject.Missing>()
            loggedJson().get(LogContextConfiguration.STORAGE_KEY).stringValue() shouldBe KEY
        }

    @Test
    @DisplayName("an unreachable store names the key the same way")
    fun `writes the storage key beside the message when the store cannot be read`() =
        runTest {
            val outcome = readerFor(IOException("connection reset")).read(KEY)

            outcome.shouldBeInstanceOf<ImageObject.Unavailable>()
            loggedJson().get(LogContextConfiguration.STORAGE_KEY).stringValue() shouldBe KEY
        }

    // The failure mode is a field that is also in the sentence, which leaves the prose free to drift.
    @Test
    fun `leaves the key out of the message text`() =
        runTest {
            readerFor(NoSuchKeyException.builder().build()).read(KEY)

            val message = appender.list.single().formattedMessage

            message.contains(KEY) shouldBe false
        }

    /**
     * The regression this could introduce silently: `logger.warn(e) { … }` put the throwable on the
     * event; the payload form needs `cause = e`, and omitting it costs `errorType` and `stackTrace`
     * on the one line where a stack trace is the reason to read.
     */
    @Test
    fun `still carries the throwable, which is a column of its own`() =
        runTest {
            readerFor(IOException("connection reset")).read(KEY)

            val error = loggedJson().get("error").shouldNotBeNull()
            error.get("type").stringValue() shouldBe IOException::class.java.name
        }

    /** A browser that aborts an image mid-read cancels the coroutine; that is no fault of the store (#1807). */
    @Test
    fun `a cancelled read rethrows and logs nothing`() =
        runTest {
            val called = CompletableDeferred<Unit>()
            val reader = readerOn(HangingS3Client(called))
            var outcome: ImageObject? = null

            val read = launch { outcome = reader.read(KEY) }
            called.await()
            read.cancelAndJoin()

            outcome.shouldBeNull()
            appender.list.shouldBeEmpty()
        }

    @Test
    fun `a cancelled read records no outcome`() =
        runTest {
            val called = CompletableDeferred<Unit>()
            val registry = SimpleMeterRegistry()
            val properties = ImageServingProperties()
            val cache = ImageObjectCache(properties, registry)
            val controller =
                CachedImageController(
                    repositoryHolding(KEY),
                    ImageObjectReader(HangingS3Client(called), properties, cache),
                    ImageServingMetrics(registry, cache)
                )

            val serve = launch { controller.serve(HASH, "288.jpg") }
            called.await()
            serve.cancelAndJoin()

            registry.find(ImageServingMetrics.SERVED).counters().sumOf { it.count() } shouldBe 0.0
        }

    private fun readerFor(failure: Exception): ImageObjectReader = readerOn(FailingS3Client(failure))

    private fun readerOn(client: S3AsyncClient): ImageObjectReader {
        val properties = ImageServingProperties()
        return ImageObjectReader(client, properties, ImageObjectCache(properties, SimpleMeterRegistry()))
    }

    /**
     * Answers only the lookup the controller makes. A proxy rather than a hand-written fake, because
     * `CoroutineCrudRepository` has a dozen members; the suspend call returns without suspending.
     */
    private fun repositoryHolding(storageKey: String): CachedImageRepository =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CachedImageRepository::class.java)) { _, method, _ ->
            check(method.name == "findStorageKey") { "unexpected call to ${method.name}" }
            storageKey
        } as CachedImageRepository

    /**
     * The single line captured as the pod writes it: `StructuredLogEncoder` in `ecs` mode is what
     * the chart's `LOGGING_STRUCTURED_FORMAT_CONSOLE` selects.
     */
    private fun loggedJson(): tools.jackson.databind.JsonNode {
        val context = LoggerContext().apply { putObject(Environment::class.java.name, MockEnvironment()) }
        val encoder =
            StructuredLogEncoder().apply {
                setFormat("ecs")
                setContext(context)
                start()
            }
        val json = String(encoder.encode(appender.list.single())).also { encoder.stop() }
        return JsonMapper.builder().build().readTree(json)
    }

    /**
     * Fails every read. Every method on `S3AsyncClient` is a default, so the one call site plus the
     * two `SdkClient` requires is the whole implementation.
     */
    private class FailingS3Client(
        private val failure: Exception
    ) : S3AsyncClient {
        override fun serviceName(): String = "s3"

        override fun close() = Unit

        override fun <ReturnT : Any?> getObject(
            getObjectRequest: GetObjectRequest,
            asyncResponseTransformer: AsyncResponseTransformer<GetObjectResponse, ReturnT>
        ): CompletableFuture<ReturnT> = CompletableFuture.failedFuture(failure)
    }

    /** Never answers, like a read still in flight; [called] says the request went out. */
    private class HangingS3Client(
        private val called: CompletableDeferred<Unit>
    ) : S3AsyncClient {
        override fun serviceName(): String = "s3"

        override fun close() = Unit

        override fun <ReturnT : Any?> getObject(
            getObjectRequest: GetObjectRequest,
            asyncResponseTransformer: AsyncResponseTransformer<GetObjectResponse, ReturnT>
        ): CompletableFuture<ReturnT> = CompletableFuture<ReturnT>().also { called.complete(Unit) }
    }

    private companion object {
        const val HASH = "0f4b0f4b0f4b0f4b0f4b0f4b0f4b0f4b0f4b0f4b0f4b0f4b0f4b0f4b0f4b0f4b"
        const val KEY = "images/derived/0f4b/288.jpg"
    }
}
