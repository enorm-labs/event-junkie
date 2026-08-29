package de.norm.events.image

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.util.unit.DataSize
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the cache promises the serving route.
 *
 * **That Caffeine evicts is not asserted here.** It is Caffeine's documented contract, and a test
 * that inserts until something disappears asserts a schedule rather than a rule. What is asserted is
 * the half this code owns: which outcomes are kept, and that a ceiling too small to hold anything
 * still returns the right bytes.
 */
class ImageObjectCacheTest {
    @Test
    @DisplayName("a key read once is not read again")
    fun `serves the second request without loading`() =
        runTest {
            val loads = AtomicInteger()
            val cache = cacheOf(DataSize.ofMegabytes(1))

            repeat(2) { cache.get(KEY) { counted(loads, ImageObject.Found(BYTES)) } }

            assertEquals(1, loads.get())
        }

    @Test
    fun `the bytes come back unchanged`() =
        runTest {
            val cache = cacheOf(DataSize.ofMegabytes(1))
            cache.get(KEY) { ImageObject.Found(BYTES) }

            val second = cache.get(KEY) { ImageObject.Found(ByteArray(0)) }

            assertContentEquals(BYTES, assertIs<ImageObject.Found>(second).bytes)
        }

    @Test
    fun `two keys do not share an entry`() =
        runTest {
            val cache = cacheOf(DataSize.ofMegabytes(1))
            cache.get(KEY) { ImageObject.Found(BYTES) }

            val other = cache.get("$KEY-other") { ImageObject.Found(OTHER_BYTES) }

            assertContentEquals(OTHER_BYTES, assertIs<ImageObject.Found>(other).bytes)
        }

    // A row promising an object that is not there can be fixed by a re-import, and a store we cannot
    // reach comes back. Keeping either would outlast the fault that produced it.
    @Test
    @DisplayName("a missing object is asked about again")
    fun `does not keep a missing object`() =
        runTest {
            val loads = AtomicInteger()
            val cache = cacheOf(DataSize.ofMegabytes(1))

            repeat(2) { cache.get(KEY) { counted(loads, ImageObject.Missing) } }

            assertEquals(2, loads.get())
        }

    @Test
    @DisplayName("an unreachable store is asked about again")
    fun `does not keep an unavailable store`() =
        runTest {
            val loads = AtomicInteger()
            val cache = cacheOf(DataSize.ofMegabytes(1))

            repeat(2) { cache.get(KEY) { counted(loads, ImageObject.Unavailable) } }

            assertEquals(2, loads.get())
        }

    // The issue's own requirement: it is a cache and not a store, so a ceiling that holds nothing
    // must leave the route correct and only slower.
    @Test
    @DisplayName("a ceiling too small to hold anything still serves the bytes")
    fun `stays correct when nothing can be retained`() =
        runTest {
            val cache = cacheOf(DataSize.ofBytes(0))
            cache.get(KEY) { ImageObject.Found(BYTES) }

            val second = cache.get(KEY) { ImageObject.Found(BYTES) }

            assertContentEquals(BYTES, assertIs<ImageObject.Found>(second).bytes)
        }

    // The meters are how the default size stops being an estimate. A binding that silently stopped
    // registering would leave the number unarguable again.
    @Test
    fun `registers its meters under the images cache`() =
        runTest {
            val registry = SimpleMeterRegistry()
            ImageObjectCache(propertiesOf(DataSize.ofMegabytes(1)), registry)
                .get(KEY) { ImageObject.Found(BYTES) }

            assertTrue(registry.meters.any { it.id.getTag("cache") == "images" })
        }

    private fun cacheOf(maxSize: DataSize) = ImageObjectCache(propertiesOf(maxSize), SimpleMeterRegistry())

    private fun propertiesOf(maxSize: DataSize) = ImageServingProperties(cache = ImageServingProperties.Cache(size = maxSize))

    private fun counted(
        loads: AtomicInteger,
        result: ImageObject
    ): ImageObject {
        loads.incrementAndGet()
        return result
    }

    private companion object {
        const val KEY = "images/derived/0f4b/288.jpg"
        val BYTES = ByteArray(32) { it.toByte() }
        val OTHER_BYTES = ByteArray(16) { (it + 1).toByte() }
    }
}
