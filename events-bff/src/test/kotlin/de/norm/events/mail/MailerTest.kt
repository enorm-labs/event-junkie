package de.norm.events.mail

import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.mail.Message
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.net.ServerSocket

class MailerTest {
    @JvmField
    @RegisterExtension
    val greenMail = GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())

    private val registry = SimpleMeterRegistry()

    private val message = MailMessage("sign-in", "visitor@example.org", "Your sign-in link", "Open the link.", "<p>Open the link.</p>")

    private fun mailer(sender: JavaMailSenderImpl = sender(greenMail.smtp.port)) = Mailer(sender, FROM, registry)

    private fun sender(
        port: Int,
        timeoutMillis: Int = 2000
    ) = JavaMailSenderImpl().apply {
        host = "localhost"
        this.port = port
        javaMailProperties["mail.smtp.connectiontimeout"] = timeoutMillis
        javaMailProperties["mail.smtp.timeout"] = timeoutMillis
    }

    private fun count(outcome: String) = registry.timer(Mailer.SENT, Mailer.TAG_KIND, "sign-in", Mailer.TAG_OUTCOME, outcome).count()

    @Test
    fun `sends text and HTML from the configured sender`() {
        mailer().send(message).block()

        val received = greenMail.receivedMessages.single()
        received.from.single().toString() shouldBe FROM
        received.getRecipients(Message.RecipientType.TO).single().toString() shouldBe "visitor@example.org"
        received.subject shouldBe "Your sign-in link"
        GreenMailUtil.getBody(received) shouldContain "Open the link."
        GreenMailUtil.getBody(received) shouldContain "<p>Open the link.</p>"
        count(Mailer.OUTCOME_SENT) shouldBe 1
    }

    @Test
    fun `a refused connection fails the send and counts as failed`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        shouldThrow<MailException> { mailer(sender(closedPort)).send(message).block() }

        count(Mailer.OUTCOME_FAILED) shouldBe 1
    }

    @Test
    fun `a server that never answers times out`() {
        // Bound but never accepting: the connection completes in the backlog and no greeting ever comes.
        ServerSocket(0).use { silent ->
            shouldThrow<MailException> { mailer(sender(silent.localPort, timeoutMillis = 300)).send(message).block() }
        }

        count(Mailer.OUTCOME_FAILED) shouldBe 1
    }

    @Test
    fun `the SMTP conversation runs off the calling thread`() {
        var thread = ""
        val recording =
            object : JavaMailSenderImpl() {
                override fun send(mimeMessage: MimeMessage) {
                    thread = Thread.currentThread().name
                }
            }

        mailer(recording).send(message).block()

        thread shouldStartWith "boundedElastic"
    }

    @Test
    fun `the address stays out of the message's string form`() {
        message.toString() shouldNotContain "visitor@example.org"
    }

    private companion object {
        const val FROM = "Event Junkie <noreply@notify.event-junkie.de>"
    }
}
