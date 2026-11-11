package de.norm.events.scraper

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class JsonExtensionsTest {
    private val post =
        SCRAPER_JSON.readTree(
            """
            {
              "title": {"rendered": " Rock &amp; Roll "},
              "excerpt": {"rendered": "  "},
              "content": {"protected": false},
              "slug": "rock-and-roll"
            }
            """.trimIndent()
        )

    @Test
    fun `reads a rendered field trimmed, with its entities still encoded`() {
        post.wpRendered("title") shouldBe "Rock &amp; Roll"
    }

    @Test
    fun `reads a blank, missing or plain field as absent`() {
        post.wpRendered("excerpt").shouldBeNull()
        post.wpRendered("content").shouldBeNull()
        post.wpRendered("slug").shouldBeNull()
        post.wpRendered("missing").shouldBeNull()
    }

    @Test
    fun `reads well-formed JSON into a tree and logs nothing`() {
        val (node, logs) = capturing { SCRAPER_JSON.readTreeOrNull("""{"items": [1, 2]}""", it) { "Failed to parse the feed" } }

        node.shouldNotBeNull().path("items").size() shouldBe 2
        logs.shouldBeEmpty()
    }

    @Test
    fun `reads malformed JSON as null and logs the message at WARN with the exception`() {
        val (node, logs) = capturing { SCRAPER_JSON.readTreeOrNull("""{"items": [1, 2""", it) { "Failed to parse the feed" } }

        node.shouldBeNull()
        logs shouldHaveSize 1
        logs.single().level shouldBe Level.WARN
        logs.single().formattedMessage shouldBe "Failed to parse the feed"
        logs
            .single()
            .throwableProxy
            .shouldNotBeNull()
            .className shouldStartWith "tools.jackson."
    }

    private fun <T> capturing(block: (KLogger) -> T): Pair<T, List<ILoggingEvent>> {
        val name = "${JsonExtensionsTest::class.java.name}.readTreeOrNull"
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logbackLogger = LoggerFactory.getLogger(name) as Logger
        logbackLogger.addAppender(appender)
        try {
            return block(KotlinLogging.logger(name)) to appender.list.toList()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
    }
}
