package de.norm.events.image

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins the rule ADR-019 comes down to. The switched-off case is the important one: serving is
 * off until an environment has a full set of derivatives, and blanking an image instead of
 * passing the venue's URL through would empty every card. The switched-on miss is the other:
 * null is deliberate, since falling back to the venue would reinstate the disclosure caching
 * exists to remove (#792), silently, for the images we happen not to hold.
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
        // 96 CSS px at 2x and 3x; 768 and 1536 exist and are not offered, ten times the bytes a card draws.
        val served = serving().serve(poster, CARD)

        served.url shouldBe "/api/images/$HASH/192.jpg"
        srcsetFor(served, "image/jpeg") shouldBe "/api/images/$HASH/192.jpg 192w, /api/images/$HASH/288.jpg 288w"
    }

    @Test
    @DisplayName("a poster card is offered 512 upwards, not the thumbnail widths")
    fun `the poster band starts at the step that was added for it`() {
        // Without 512 this band would hold 768 alone, and a 1x laptop would download 36 KB for a slot
        // 512 serves in 16 KB (#1245). 480 at 3x is 1440, so 1536 stays a detail-page file.
        val served = serving().serve(poster, POSTER_CARD)

        served.url shouldBe "/api/images/$HASH/512.jpg"
        srcsetFor(served, "image/jpeg") shouldBe
            "/api/images/$HASH/512.jpg 512w, /api/images/$HASH/768.jpg 768w"
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
        // Soft rather than absent: refusing an image narrower than the slot would look like it is missing.
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
        // AVIF and WebP alone would be a blank space on anything that cannot decode them; a
        // half-generated image waits for the next pass.
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

    // Both or neither, decided here so a template can trust the pair (#848).
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

        /**
         * What a poster card passes: a two-column `max-w-5xl` grid puts the card at about 474 CSS px,
         * and the width step that serves it (512) is what this holds.
         */
        const val POSTER_CARD = 480

        val ALL_WIDTHS = setOf(192, 288, 512, 768, 1536)
    }
}
