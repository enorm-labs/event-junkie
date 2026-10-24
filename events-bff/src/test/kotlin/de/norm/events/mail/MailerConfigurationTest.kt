package de.norm.events.mail

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** Without a host there is no [Mailer], so a cluster without the mail Secret starts (ADR-054). */
class MailerConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration::class.java))
            .withUserConfiguration(Mailer::class.java)
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withPropertyValues("app.mail.from=noreply@notify.event-junkie.de")

    @Test
    fun `no mailer without a host`() {
        runner.run { context -> context.getBeansOfType(Mailer::class.java).values.shouldBeEmpty() }
    }

    @Test
    fun `a mailer once a host is set`() {
        runner
            .withPropertyValues("spring.mail.host=smtp.tem.scaleway.com")
            .run { context -> context.getBeansOfType(Mailer::class.java).values shouldHaveSize 1 }
    }
}
