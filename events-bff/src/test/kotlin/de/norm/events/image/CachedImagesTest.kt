package de.norm.events.image

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the rule ADR-019 comes down to, in the one place it is written.
 *
 * **The switched-off case is the important one.** Serving is off everywhere until an environment has
 * a full set of derivatives, and a change that made it blank an image instead of passing the venue's
 * URL through would empty every card on every environment that has not enabled it yet.
 *
 * **The switched-on miss is the other one.** Returning null there is deliberate: falling back to the
 * venue would reinstate the disclosure that caching exists to remove
 * ([#792](https://github.com/enorm-labs/event-junkie/issues/792)), and it would do it silently, only
 * for the images we happen not to hold.
 */
class CachedImagesTest {
    private val poster = "https://venue.test/poster.jpg"

    private fun serving(widths: Set<Int>) = CachedImages(mapOf(poster to ServableImage(HASH, widths.toSortedSet())), "/api/images")

    @Test
    @DisplayName("while serving is off, a venue's URL is handed out unchanged")
    fun `disabled passes the source url through`() {
        val images = CachedImages.disabled()

        assertEquals(poster, images.rewrite(poster, 288))
        assertNull(images.rewrite(null, 288))
    }

    @Test
    fun `a held image becomes a path on our own origin`() {
        assertEquals("/api/images/$HASH/288.jpg", serving(setOf(192, 288, 768, 1536)).rewrite(poster, 288))
    }

    @Test
    @DisplayName("an image we do not hold is reported absent, not hotlinked")
    fun `a miss is null rather than the source url`() {
        assertNull(serving(setOf(288)).rewrite("https://venue.test/other.jpg", 288))
    }

    @Test
    fun `a null source url stays null`() {
        assertNull(serving(setOf(288)).rewrite(null, 288))
    }

    @Test
    @DisplayName("the narrowest derivative that covers the slot is chosen")
    fun `width selection rounds up`() {
        val images = serving(setOf(192, 288, 768, 1536))

        assertEquals("/api/images/$HASH/192.jpg", images.rewrite(poster, 100))
        assertEquals("/api/images/$HASH/768.jpg", images.rewrite(poster, 704))
    }

    @Test
    @DisplayName("a slot wider than anything we hold gets the widest we hold")
    fun `width selection falls back to the widest`() {
        // Soft rather than absent. An image whose largest derivative is narrower than the slot is
        // upscaled by the browser; refusing it would look like the image is missing, and it is not.
        assertEquals("/api/images/$HASH/288.jpg", serving(setOf(192, 288)).rewrite(poster, 768))
    }

    @Test
    fun `an empty width set has nothing to serve`() {
        assertNull(serving(emptySet()).rewrite(poster, 288))
    }

    @Test
    @DisplayName("the base path Spring serves under is part of the URL")
    fun `the prefix is carried through`() {
        val local = CachedImages(mapOf(poster to ServableImage(HASH, sortedSetOf(288))), "/images")

        assertEquals("/images/$HASH/288.jpg", local.rewrite(poster, 288))
    }

    private companion object {
        const val HASH = "0f4b2c1d5e6a7b8c9d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e"
    }
}
