package de.norm.events.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [ResponseCache].
 *
 * The two that matter most are the ones a reader would otherwise have to take on trust: that two
 * endpoints cannot collide, and that a failed load is not remembered.
 */
class ResponseCacheTest {
    private fun cache(
        ttlSeconds: Long = 60,
        maximumItems: Long = 1000
    ) = ResponseCache(SimpleMeterRegistry(), ttlSeconds, maximumItems)

    private data class Key(
        val slug: String
    )

    /** Same shape, different type — the collision a single string-keyed cache would have. */
    private data class OtherKey(
        val slug: String
    )

    @Test
    fun `loads once and serves every later call from memory`(): Unit =
        runBlocking {
            val loads = AtomicInteger()
            val cache = cache()

            repeat(3) {
                val served =
                    cache.get(Key("lido")) {
                        loads.incrementAndGet()
                        "value"
                    }
                served shouldBe "value"
            }

            loads.get() shouldBe 1
        }

    @Test
    fun `keeps two key types apart even when their fields match`(): Unit =
        runBlocking {
            val cache = cache()

            cache.get(Key("lido")) { "events" } shouldBe "events"
            cache.get(OtherKey("lido")) { "venues" } shouldBe "venues"
            cache.get(Key("lido")) { "unused" } shouldBe "events"
        }

    @Test
    fun `remembers nothing when the load fails`(): Unit =
        runBlocking {
            val cache = cache()

            shouldThrow<IllegalStateException> { cache.get(Key("lido")) { error("database is gone") } }

            cache.size() shouldBe 0
            cache.get(Key("lido")) { "recovered" } shouldBe "recovered"
        }

    @Test
    fun `loads again once the entry has expired`(): Unit =
        runBlocking {
            val loads = AtomicInteger()
            // Zero rather than a sleep: an expiry test that waits is a slow test that still races.
            val cache = cache(ttlSeconds = 0)

            repeat(2) {
                val served =
                    cache.get(Key("lido")) {
                        loads.incrementAndGet()
                        "value"
                    }
                served shouldBe "value"
            }

            loads.get() shouldBe 2
        }

    @Test
    fun `counts a list by its items rather than as one entry`(): Unit =
        runBlocking {
            // Two responses of six items each exceed a ten-item bound, so the cache cannot hold both.
            val cache = cache(maximumItems = 10)

            cache.get(Key("first")) { List(6) { "event" } } shouldHaveSize 6
            cache.get(Key("second")) { List(6) { "event" } } shouldHaveSize 6

            cache.size() shouldBe 1
        }

    @Test
    fun `counts a page by the items it carries`(): Unit =
        runBlocking {
            val cache = cache(maximumItems = 10)
            val page = PageResponse(content = List(6) { "event" }, page = 0, size = 6, totalElements = 6, totalPages = 1)

            cache.get(Key("first")) { page } shouldBe page
            cache.get(Key("second")) { page } shouldBe page

            cache.size() shouldBe 1
        }
}
