package de.norm.events.image

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * [ImageStorage] without a client, which is the state a local run and CI are in.
 *
 * **The distinction these pin is the one that matters.** "Nothing is configured" and "the store
 * failed" both surface as a null key and mean opposite things — the first is fine and the second is
 * our own infrastructure. [ImageCacheService] branches on [ImageStorage.isEnabled] to tell them
 * apart, so if that ever collapses into one answer a storage outage starts looking like a corpus of
 * bad URLs.
 */
class ImageStorageTest {
    private val properties = ImageStorageProperties(prefix = "staging")

    @Test
    fun `is disabled when no client was built`() {
        ImageStorage(client = null, properties = properties).isEnabled() shouldBe false
    }

    @Test
    fun `stores nothing and says so when disabled`() =
        runTest {
            ImageStorage(client = null, properties = properties)
                .storeOriginal("abc123", "image/png", ByteArray(4))
                .shouldBeNull()
        }

    @Test
    fun `puts the environment prefix first, so a sweep can scope itself with a prefix query`() {
        // The order is load-bearing rather than cosmetic. Keys are content addressed, so staging and
        // production compute the same hash for the same venue image; the prefix is the only thing
        // that keeps one environment's sweep away from the other's objects (#270, ADR-019 §2.8).
        ImageStorage(client = null, properties = properties).originalKey("abc123") shouldBe "staging/originals/abc123"
    }

    @Test
    fun `names the content hash of every key it writes`() {
        val storage = ImageStorage(client = null, properties = properties)

        storage.contentHashOf(storage.originalKey("abc123")) shouldBe "abc123"
        storage.contentHashOf(storage.derivativeKey("abc123", 192, "avif")) shouldBe "abc123"
    }

    @Test
    fun `names nothing for a key it did not write, so the sweep leaves it alone`() {
        // The sweep deletes what this names. A key of an unknown shape belongs to something else,
        // and guessing at it is how a sweep reaches an object it does not own.
        val storage = ImageStorage(client = null, properties = properties)

        storage.contentHashOf("production/originals/abc123").shouldBeNull()
        storage.contentHashOf("staging/something-else/file.bin").shouldBeNull()
        storage.contentHashOf("staging/originals/abc123/extra").shouldBeNull()
        storage.contentHashOf("staging/originals/").shouldBeNull()
    }

    @Test
    fun `credentials decide whether a client is configured`() {
        ImageStorageProperties().isConfigured() shouldBe false
        ImageStorageProperties(accessKey = "a", secretKey = "").isConfigured() shouldBe false
        ImageStorageProperties(accessKey = " ", secretKey = " ").isConfigured() shouldBe false
        ImageStorageProperties(accessKey = "a", secretKey = "b").isConfigured() shouldBe true
    }
}
