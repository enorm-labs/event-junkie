package de.norm.events.image

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

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

        images.serve(poster, CARD).url shouldBe poster
        images.serve(poster, CARD).sources.isEmpty() shouldBe true
        images.serve(null, CARD).url shouldBe null
    }

    @Test
    @DisplayName("an image we do not hold is reported absent, not hotlinked")
    fun `a miss is null rather than the source url`() {
        serving().serve("https://venue.test/other.jpg", CARD) shouldBe ServedImage.ABSENT
    }

    @Test
    fun `a null source url stays null`() {
        serving().serve(null, CARD) shouldBe ServedImage.ABSENT
    }

    // --- which widths a slot is offered ------------------------------------------------------

    @Test
    @DisplayName("a card is offered the widths between its own and three times it")
    fun `the card band covers the plausible pixel ratios`() {
        // 96 CSS px at 2x and 3x. The 768 and 1536 files exist and are not offered: a list of twenty
        // cards carrying them is ten times the bytes anything will draw.
        val served = serving().serve(poster, CARD)

        served.url shouldBe "/api/images/$HASH/192.jpg"
        srcsetFor(served, "image/jpeg") shouldBe "/api/images/$HASH/192.jpg 192w, /api/images/$HASH/288.jpg 288w"
    }

    @Test
    fun `the detail band covers the column at one and two times`() {
        val served = serving().serve(poster, DETAIL)

        served.url shouldBe "/api/images/$HASH/768.jpg"
        srcsetFor(served, "image/jpeg") shouldBe "/api/images/$HASH/768.jpg 768w, /api/images/$HASH/1536.jpg 1536w"
    }

    @Test
    @DisplayName("a slot wider than anything we hold gets the widest we hold")
    fun `an empty band falls back to the widest`() {
        // Soft rather than absent. An image whose largest derivative is narrower than the slot is
        // upscaled by the browser; refusing it would look like the image is missing, and it is not.
        val served = serving(widths = setOf(192)).serve(poster, DETAIL)

        served.url shouldBe "/api/images/$HASH/192.jpg"
    }

    // --- which formats a browser is offered --------------------------------------------------

    @Test
    @DisplayName("the formats are offered best first, which is what <picture> requires")
    fun `sources are ordered`() {
        val served = serving().serve(poster, CARD)

        served.sources.map { it.type } shouldBe listOf("image/avif", "image/webp", "image/jpeg")
        srcsetFor(served, "image/avif") shouldBe "/api/images/$HASH/192.avif 192w, /api/images/$HASH/288.avif 288w"
    }

    @Test
    @DisplayName("an image with no JPEG is not served at all")
    fun `the fallback format is required`() {
        // AVIF and WebP alone would be a blank space on anything that cannot decode them, and the
        // `<img src>` inside `<picture>` has no safe value. A half-generated image waits for the
        // next pass instead.
        serving(formats = listOf("avif", "webp")).serve(poster, CARD) shouldBe ServedImage.ABSENT
    }

    @Test
    fun `a format missing one width offers only the widths it has`() {
        val served = serving(mapOf("avif" to setOf(288), "jpg" to setOf(192, 288))).serve(poster, CARD)

        srcsetFor(served, "image/avif") shouldBe "/api/images/$HASH/288.avif 288w"
        srcsetFor(served, "image/jpeg") shouldBe "/api/images/$HASH/192.jpg 192w, /api/images/$HASH/288.jpg 288w"
    }

    @Test
    @DisplayName("the base path Spring serves under is part of every URL")
    fun `the prefix is carried through`() {
        val local =
            CachedImages(mapOf(poster to ServableImage(HASH, mapOf("jpg" to sortedSetOf(192)))), "/images")

        local.serve(poster, CARD).url shouldBe "/images/$HASH/192.jpg"
    }

    // Both or neither, decided here so a template can trust the pair. Reporting one reserves no
    // space and is the same layout shift as reporting none (#848).
    @Test
    fun `an image measured on both axes reports both`() {
        val measured = imageWith(intrinsicWidth = 1200, intrinsicHeight = 630).serve(poster, CARD)

        measured.intrinsicWidth shouldBe 1200
        measured.intrinsicHeight shouldBe 630
    }

    @Test
    @DisplayName("an image measured on one axis reports neither")
    fun `a half-measured image reports nothing`() {
        val half = imageWith(intrinsicWidth = 1200, intrinsicHeight = null).serve(poster, CARD)

        half.intrinsicWidth shouldBe null
        half.intrinsicHeight shouldBe null
    }

    private fun imageWith(
        intrinsicWidth: Int?,
        intrinsicHeight: Int?
    ) = CachedImages(
        mapOf(
            poster to
                ServableImage(HASH, mapOf("jpg" to sortedSetOf(192)), intrinsicWidth, intrinsicHeight)
        ),
        "/api/images"
    )

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
