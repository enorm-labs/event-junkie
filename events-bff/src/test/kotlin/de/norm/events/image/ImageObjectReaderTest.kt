package de.norm.events.image

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.norm.events.LogContextConfiguration
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * What the two storage warnings carry, asserted on the **ECS JSON** rather than on the log event
 * (#980).
 *
 * The position is the point. A value welded into the sentence reads exactly like one beside it, so
 * only an assertion at the top level of the serialised object separates a field the log store can
 * filter on from prose that merely looks structured.
 *
 * **A fake client rather than MinIO.** [CachedImageServingTest] uses a real S3 API because what it
 * proves is that this application's own configuration reads back a key the importer wrote. Nothing
 * here needs a bucket: both assertions are about a call that failed.
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

            assertIs<ImageObject.Missing>(outcome)
            assertEquals(KEY, loggedJson().get(LogContextConfiguration.STORAGE_KEY).stringValue())
        }

    @Test
    @DisplayName("an unreachable store names the key the same way")
    fun `writes the storage key beside the message when the store cannot be read`() =
        runTest {
            val outcome = readerFor(IOException("connection reset")).read(KEY)

            assertIs<ImageObject.Unavailable>(outcome)
            assertEquals(KEY, loggedJson().get(LogContextConfiguration.STORAGE_KEY).stringValue())
        }

    // The failure mode is not an absent field. It is a field that is *also* in the sentence, which
    // reads as working and leaves the prose free to drift out of step with it.
    @Test
    fun `leaves the key out of the message text`() =
        runTest {
            readerFor(NoSuchKeyException.builder().build()).read(KEY)

            val message = appender.list.single().formattedMessage

            assertFalse(message.contains(KEY))
        }

    /**
     * The regression this change could introduce silently.
     *
     * `logger.warn(e) { … }` put the throwable on the event without anyone having to think about it.
     * The payload form needs `cause = e` written out, and omitting it costs `errorType` and
     * `stackTrace` — two separate columns the collector lifts from `error`, on the one line where a
     * stack trace is the reason to be reading at all.
     */
    @Test
    fun `still carries the throwable, which is a column of its own`() =
        runTest {
            readerFor(IOException("connection reset")).read(KEY)

            val error = assertNotNull(loggedJson().get("error"))
            assertEquals(IOException::class.java.name, error.get("type").stringValue())
        }

    private fun readerFor(failure: Exception): ImageObjectReader {
        val properties = ImageServingProperties()
        return ImageObjectReader(FailingS3Client(failure), properties, ImageObjectCache(properties, SimpleMeterRegistry()))
    }

    /**
     * The single line captured, serialised exactly as the pod writes it.
     *
     * `StructuredLogEncoder` in `ecs` mode is what the chart's `LOGGING_STRUCTURED_FORMAT_CONSOLE`
     * selects, so this is the closest thing to the shipped line a unit test can hold.
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
     * Fails every read, which is all either warning needs.
     *
     * Every method on `S3AsyncClient` is a default, so overriding the one call site reaches plus the
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

    private companion object {
        const val KEY = "images/derived/0f4b/288.jpg"
    }
}
