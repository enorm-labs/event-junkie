package de.norm.events.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.nio.charset.StandardCharsets

private val logger = KotlinLogging.logger {}

/**
 * Sends a [MailMessage] over SMTP (ADR-054). Exists only when `spring.mail.host` is set, the same
 * condition Boot builds the [JavaMailSender] on, so a cluster without the mail Secret starts and a
 * caller sees no bean rather than a failure at the first send.
 *
 * `JavaMailSender` blocks for the whole SMTP conversation, so every send runs on `boundedElastic`
 * and never on a Netty event loop. The address is never logged (LEGAL.md §7.5): the line and the
 * timer carry the kind and the outcome.
 */
@Component
@ConditionalOnProperty("spring.mail.host")
class Mailer(
    private val sender: JavaMailSender,
    @Value("\${app.mail.from}") private val from: String,
    private val registry: MeterRegistry
) {
    fun send(message: MailMessage): Mono<Unit> {
        val sample = Timer.start(registry)
        return Mono
            .fromCallable { sender.send(mime(message)) }
            .subscribeOn(Schedulers.boundedElastic())
            .doOnSuccess {
                sample.stop(timer(message.kind, OUTCOME_SENT))
                logger.info { "Sent ${message.kind} mail" }
            }.doOnError { e ->
                sample.stop(timer(message.kind, OUTCOME_FAILED))
                logger.warn(e) { "Could not send ${message.kind} mail" }
            }
    }

    private fun mime(message: MailMessage) =
        sender.createMimeMessage().also { mime ->
            MimeMessageHelper(mime, message.html != null, StandardCharsets.UTF_8.name()).apply {
                setFrom(from)
                setTo(message.to)
                setSubject(message.subject)
                if (message.html == null) setText(message.text) else setText(message.text, message.html)
            }
        }

    private fun timer(
        kind: String,
        outcome: String
    ) = registry.timer(SENT, TAG_KIND, kind, TAG_OUTCOME, outcome)

    companion object {
        /** `bff.mail.sent{kind,outcome}`: a count and a duration in one meter. */
        const val SENT = "bff.mail.sent"
        const val TAG_KIND = "kind"
        const val TAG_OUTCOME = "outcome"
        const val OUTCOME_SENT = "sent"
        const val OUTCOME_FAILED = "failed"
    }
}
