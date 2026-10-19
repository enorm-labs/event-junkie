package de.norm.events.ai

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration

class AnthropicClientTest {
    @Test
    fun `three callers on one registry each register their own pool gauges, tagged with the role`() {
        val registry = SimpleMeterRegistry()
        // Micrometer warns once per JVM and then logs at DEBUG, so DEBUG is the level that catches a repeat.
        val log = LoggerFactory.getLogger(MeterRegistry::class.java) as Logger
        val previousLevel = log.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        log.level = Level.DEBUG
        log.addAppender(appender)
        try {
            ROLES.forEach { AnthropicClient(it, SETTINGS, registry, Dispatchers.IO) }
        } finally {
            log.detachAppender(appender)
            log.level = previousLevel
        }

        withClue("no gauge is registered twice") {
            appender.list.filter { it.formattedMessage.contains("already registered") }.shouldBeEmpty()
        }
        val gauges = registry.find("okhttp.pool.connection.count").gauges()
        withClue("one sync and one async client per role, each with an active and an idle gauge") { gauges.size shouldBe ROLES.size * 4 }
        gauges.map { it.id.getTag(AnthropicClient.ROLE_TAG) }.distinct() shouldContainExactlyInAnyOrder ROLES
    }

    private companion object {
        val ROLES = listOf("translation", "classification", "instagram")
        val SETTINGS = AnthropicSettings("test-key", "claude-haiku-4-5", Duration.ofSeconds(5), maxRetries = 0, maxTokens = 100)
    }
}
