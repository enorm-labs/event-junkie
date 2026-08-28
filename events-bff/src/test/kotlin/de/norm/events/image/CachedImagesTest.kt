package de.norm.events.image

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private fun serving(
        widths: Set<Int> = ALL_WIDTHS,
        formats: List<String> = ImageFormats.ORDERED
    ) = serving(formats.associateWith { widths })

    private fun serving(widthsByFormat: Map<String, Set<Int>>) =
        CachedImages(
            mapOf(poster to ServableImage(HASH, widthsByFormat.mapValues { (_, widths) -> widths.toSortedSet() })),
            "/api/images"
        )

    @Test
    @DisplayName("while serving is off, a venue's URL is handed out unchanged")
    fun `disabled passes the source url through`() {
        val images = CachedImages.disabled()

        assertEquals(poster, images.serve(poster, CARD).url)
        assertTrue(images.serve(poster, CARD).sources.isEmpty())
        assertNull(images.serve(null, CARD).url)
    }

    @Test
    @DisplayName("an image we do not hold is reported absent, not hotlinked")
    fun `a miss is null rather than the source url`() {
        assertEquals(ServedImage.ABSENT, serving().serve("https://venue.test/other.jpg", CARD))
    }

    @Test
    fun `a null source url stays null`() {
        assertEquals(ServedImage.ABSENT, serving().serve(null, CARD))
    }

    // --- which widths a slot is offered ------------------------------------------------------

    @Test
    @DisplayName("a card is offered the widths between its own and three times it")
    fun `the card band covers the plausible pixel ratios`() {
        // 96 CSS px at 2x and 3x. The 768 and 1536 files exist and are not offered: a list of twenty
        // cards carrying them is ten times the bytes anything will draw.
        val served = serving().serve(poster, CARD)

        assertEquals("/api/images/$HASH/192.jpg", served.url)
        assertEquals("/api/images/$HASH/192.jpg 192w, /api/images/$HASH/288.jpg 288w", srcsetFor(served, "image/jpeg"))
    }

    @Test
    fun `the detail band covers the column at one and two times`() {
        val served = serving().serve(poster, DETAIL)

        assertEquals("/api/images/$HASH/768.jpg", served.url)
        assertEquals("/api/images/$HASH/768.jpg 768w, /api/images/$HASH/1536.jpg 1536w", srcsetFor(served, "image/jpeg"))
    }

    @Test
    @DisplayName("a slot wider than anything we hold gets the widest we hold")
    fun `an empty band falls back to the widest`() {
        // Soft rather than absent. An image whose largest derivative is narrower than the slot is
        // upscaled by the browser; refusing it would look like the image is missing, and it is not.
        val served = serving(widths = setOf(192)).serve(poster, DETAIL)

        assertEquals("/api/images/$HASH/192.jpg", served.url)
    }

    // --- which formats a browser is offered --------------------------------------------------

    @Test
    @DisplayName("the formats are offered best first, which is what <picture> requires")
    fun `sources are ordered`() {
        val served = serving().serve(poster, CARD)

        assertEquals(listOf("image/avif", "image/webp", "image/jpeg"), served.sources.map { it.type })
        assertEquals("/api/images/$HASH/192.avif 192w, /api/images/$HASH/288.avif 288w", srcsetFor(served, "image/avif"))
    }

    @Test
    @DisplayName("an image with no JPEG is not served at all")
    fun `the fallback format is required`() {
        // AVIF and WebP alone would be a blank space on anything that cannot decode them, and the
        // `<img src>` inside `<picture>` has no safe value. A half-generated image waits for the
        // next pass instead.
        assertEquals(ServedImage.ABSENT, serving(formats = listOf("avif", "webp")).serve(poster, CARD))
    }

    @Test
    fun `a format missing one width offers only the widths it has`() {
        val served = serving(mapOf("avif" to setOf(288), "jpg" to setOf(192, 288))).serve(poster, CARD)

        assertEquals("/api/images/$HASH/288.avif 288w", srcsetFor(served, "image/avif"))
        assertEquals("/api/images/$HASH/192.jpg 192w, /api/images/$HASH/288.jpg 288w", srcsetFor(served, "image/jpeg"))
    }

    @Test
    @DisplayName("the base path Spring serves under is part of every URL")
    fun `the prefix is carried through`() {
        val local =
            CachedImages(mapOf(poster to ServableImage(HASH, mapOf("jpg" to sortedSetOf(192)))), "/images")

        assertEquals("/images/$HASH/192.jpg", local.serve(poster, CARD).url)
    }

    private fun srcsetFor(
        served: ServedImage,
        type: String
    ) = served.sources.single { it.type == type }.srcset

    private companion object {
        const val HASH = "0f4b2c1d5e6a7b8c9d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e"

        /** What `EventService` passes: CSS pixels, not file widths. */
        const val CARD = 96
        const val DETAIL = 704

        val ALL_WIDTHS = setOf(192, 288, 768, 1536)
    }
}
