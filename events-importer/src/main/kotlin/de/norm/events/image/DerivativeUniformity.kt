package de.norm.events.image

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * Tells a blank derivative from a real one by the spread of its luminance.
 *
 * imgproxy renders frame 0 of an animated GIF, and a flyer that fades in starts black (see #2669).
 * A blank derivative counts as no image, so the card shows the title poster.
 *
 * The spread decides, not the mean: a dark flyer still has edges. [BLANK_SPREAD] sits between these
 * 192 px production derivatives:
 * - BOXHOPPING at Monster Ronson's, a GIF with a black frame 0: mean 1.0, spread 0.00.
 * - Des Rocs at Gretchen, a flat grey original: mean 44.2, spread 0.00.
 * - Berlinians at Comedy Café, blue on blue, the lowest spread of 448 real flyers: 6.91.
 * - Shadow of Intent at Metropol, the darkest real flyer: mean 9.8, spread 16.55.
 */
internal object DerivativeUniformity {
    /** Below this standard deviation of luma, on a 0–255 scale, a derivative is blank. */
    const val BLANK_SPREAD = 2.0

    private const val RED_WEIGHT = 0.299
    private const val GREEN_WEIGHT = 0.587
    private const val BLUE_WEIGHT = 0.114
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val CHANNEL_MASK = 0xFF

    /**
     * The standard deviation of the luma of [bytes], or null when the JVM cannot decode them.
     *
     * Null is not blank. A failure of our decoder says nothing about the image.
     */
    fun luminanceSpread(bytes: ByteArray): Double? {
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 } ?: return null
        val pixels = image.width.toLong() * image.height

        var sum = 0.0
        var sumOfSquares = 0.0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                val luma =
                    RED_WEIGHT * ((rgb shr RED_SHIFT) and CHANNEL_MASK) +
                        GREEN_WEIGHT * ((rgb shr GREEN_SHIFT) and CHANNEL_MASK) +
                        BLUE_WEIGHT * (rgb and CHANNEL_MASK)
                sum += luma
                sumOfSquares += luma * luma
            }
        }
        val mean = sum / pixels
        return sqrt((sumOfSquares / pixels - mean * mean).coerceAtLeast(0.0))
    }
}
