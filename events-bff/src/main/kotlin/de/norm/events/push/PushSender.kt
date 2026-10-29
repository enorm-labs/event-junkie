package de.norm.events.push

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Clock
import java.time.Duration

private val logger = KotlinLogging.logger {}

/** One browser's subscription, as the browser handed it over: the push service's URL and the browser's two keys. */
data class PushTarget(
    val endpoint: String,
    val p256dh: String,
    val auth: String
) {
    /** The endpoint identifies one browser, so it stays out of `toString` and every log line. */
    override fun toString(): String = "PushTarget(host=${URI(endpoint).host})"
}

/**
 * What the service worker shows: a title, one line, and a path on our own site that a click opens. [tag] is one fixed
 * word per kind of push, so a device replaces the open push of that kind instead of stacking a new one (#4167).
 */
data class PushMessage(
    val title: String,
    val body: String,
    val url: String,
    val tag: String
)

enum class PushResult(
    val tag: String
) {
    /** The push service took the message. */
    SENT("sent"),

    /** 404 or 410: the browser unsubscribed or the subscription expired. The caller deletes it. */
    GONE("gone"),

    /** Any other 4xx: the request was wrong, so a retry would fail the same way. Logged. */
    REJECTED("rejected"),

    /** 429, 5xx or no answer. Logged; the subscription stays. */
    FAILED("failed")
}

/**
 * Sends one encrypted message to one browser's push service (ADR-055, RFC 8030). Exists only when the VAPID keys are
 * set, so a cluster without the push Secret starts, and a caller sees no bean rather than a failure at the first send.
 *
 * The endpoint is never logged: it identifies a browser. The line and the timer carry the push service's host and
 * the outcome, and a refusal adds the push service's own reason.
 */
@Component
@ConditionalOnProperty("app.push.vapid.private-key")
@Suppress("LongParameterList") // Four collaborators and the three settings of one sender.
class PushSender(
    webClients: WebClient.Builder,
    private val json: JsonMapper,
    private val registry: MeterRegistry,
    private val clock: Clock,
    @Value("\${app.push.vapid.private-key}") privateKey: String,
    @Value("\${app.push.vapid.public-key}") publicKey: String,
    @Value("\${app.push.subject}") private val subject: String
) {
    private val keys = VapidKeys.of(privateKey, publicKey)
    private val client = webClients.build()

    val publicKey: String get() = keys.publicBase64Url

    @Suppress("TooGenericExceptionCaught") // Any failure of one send is that send's outcome; the next browser still gets its message.
    suspend fun send(
        target: PushTarget,
        message: PushMessage
    ): PushResult {
        val sample = Timer.start(registry)
        val host = URI(target.endpoint).host
        val result =
            try {
                val (status, reason) = post(target, message)
                when {
                    status.is2xxSuccessful -> PushResult.SENT
                    status.value() == NOT_FOUND || status.value() == GONE -> PushResult.GONE
                    status.value() == TOO_MANY_REQUESTS || status.is5xxServerError -> PushResult.FAILED
                    else -> PushResult.REJECTED
                }.also {
                    if (it == PushResult.REJECTED || it == PushResult.FAILED) logger.warn { "Push to $host answered ${status.value()}: $reason" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Push to $host failed" }
                PushResult.FAILED
            }
        sample.stop(registry.timer(SENT, TAG_OUTCOME, result.tag))
        return result
    }

    private suspend fun post(
        target: PushTarget,
        message: PushMessage
    ): Pair<HttpStatusCode, String> {
        val endpoint = URI(target.endpoint)
        val audience = "${endpoint.scheme}://${endpoint.authority}"
        val body =
            WebPushCrypto.encrypt(
                json.writeValueAsBytes(message),
                WebPushCrypto.fromBase64Url(target.p256dh),
                WebPushCrypto.fromBase64Url(target.auth)
            )
        return client
            .post()
            .uri(endpoint)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header("Content-Encoding", "aes128gcm")
            .header("TTL", TIME_TO_LIVE.seconds.toString())
            .header("Urgency", "normal")
            .header("Authorization", keys.authorization(audience, subject, clock.instant().plus(TOKEN_LIFETIME)))
            .bodyValue(body)
            .exchangeToMono { response ->
                if (response.statusCode().is2xxSuccessful) {
                    response.releaseBody().thenReturn(response.statusCode() to "")
                } else {
                    response
                        .bodyToMono(String::class.java)
                        .defaultIfEmpty("")
                        .map { response.statusCode() to reason(it) }
                }
            }.timeout(TIMEOUT)
            .awaitSingle()
    }

    /** The push service's own words for a refusal: FCM names a VAPID key mismatch, Apple a reason code. One line, capped. */
    private fun reason(body: String): String =
        body
            .replace(WHITESPACE, " ")
            .trim()
            .take(REASON_LENGTH)
            .ifEmpty { "no body" }

    companion object {
        /** `bff.push.sent{outcome}`: a count and a duration in one meter, as `bff.mail.sent`. */
        const val SENT = "bff.push.sent"
        const val TAG_OUTCOME = "outcome"

        /** A day: a new date is still news tomorrow, and a phone off overnight should still get it. */
        val TIME_TO_LIVE: Duration = Duration.ofDays(1)

        /** RFC 8292 allows at most 24 hours. */
        val TOKEN_LIFETIME: Duration = Duration.ofHours(12)
        val TIMEOUT: Duration = Duration.ofSeconds(10)
        private const val NOT_FOUND = 404
        private const val GONE = 410
        private const val TOO_MANY_REQUESTS = 429
        private const val REASON_LENGTH = 200
        private val WHITESPACE = Regex("\\s+")
    }
}
