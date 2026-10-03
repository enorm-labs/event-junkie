package de.norm.events.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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

    @Test
    fun `loads a key once while many callers miss it together`(): Unit =
        runBlocking(Dispatchers.Default) {
            val loads = AtomicInteger()
            val release = CompletableDeferred<Unit>()
            val cache = cache()

            val callers =
                List(CALLERS) {
                    async {
                        cache.get(Key("calendar")) {
                            loads.incrementAndGet()
                            release.await()
                            "value"
                        }
                    }
                }
            awaitLoadStarted(cache)
            release.complete(Unit)

            callers.awaitAll() shouldBe List(CALLERS) { "value" }
            loads.get() shouldBe 1
            cache.loadingCount() shouldBe 0
        }

    @Test
    fun `fails every waiting caller when the one load fails, and remembers nothing`(): Unit =
        runBlocking(Dispatchers.Default) {
            val release = CompletableDeferred<Unit>()
            val cache = cache()

            val callers =
                List(CALLERS) {
                    async {
                        runCatching {
                            cache.get(Key("calendar")) {
                                release.await()
                                error("database is gone")
                            }
                        }
                    }
                }
            awaitLoadStarted(cache)
            release.complete(Unit)

            callers.awaitAll().map { it.exceptionOrNull()?.message } shouldBe List(CALLERS) { "database is gone" }
            cache.size() shouldBe 0
            cache.loadingCount() shouldBe 0
            cache.get(Key("calendar")) { "recovered" } shouldBe "recovered"
        }

    @Test
    fun `hands the load to a waiting caller when the loading caller is cancelled`(): Unit =
        runBlocking(Dispatchers.Default) {
            val loads = AtomicInteger()
            val firstStarted = CompletableDeferred<Unit>()
            val cache = cache()

            val loader =
                launch {
                    cache.get<String>(Key("calendar")) {
                        loads.incrementAndGet()
                        firstStarted.complete(Unit)
                        awaitCancellation()
                    }
                }
            firstStarted.await()
            val waiter = async { cache.get(Key("calendar")) { "loaded by the waiter".also { loads.incrementAndGet() } } }
            yield()
            loader.cancelAndJoin()

            waiter.await() shouldBe "loaded by the waiter"
            loads.get() shouldBe 2
            cache.loadingCount() shouldBe 0
        }

    @Test
    fun `loads different keys in parallel`(): Unit =
        runBlocking(Dispatchers.Default) {
            val bothStarted = CompletableDeferred<Unit>()
            val started = AtomicInteger()
            val cache = cache()

            val loads =
                listOf("first", "second").map { slug ->
                    async {
                        cache.get(Key(slug)) {
                            if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
                            bothStarted.await()
                            slug
                        }
                    }
                }

            loads.awaitAll() shouldBe listOf("first", "second")
        }

    /** Waits until one caller holds the load, so the others are sure to find it running. */
    private suspend fun awaitLoadStarted(cache: ResponseCache) {
        while (cache.loadingCount() == 0) yield()
        repeat(CALLERS) { yield() }
    }

    private companion object {
        const val CALLERS = 20
    }
}
