package de.norm.events.image

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** JPEG bytes of a [width] by [height] image that [paint] draws, as imgproxy would return them. */
internal fun testJpeg(
    width: Int = 192,
    height: Int = 108,
    paint: (java.awt.Graphics2D) -> Unit
): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply(paint).dispose()
    return ByteArrayOutputStream().use { out ->
        ImageIO.write(image, "jpg", out)
        out.toByteArray()
    }
}

/** A flat image of one [color]. */
internal fun flatJpeg(color: Color): ByteArray =
    testJpeg { g ->
        g.color = color
        g.fillRect(0, 0, 192, 108)
    }

/** A near-black background with a band and a title in grey: low mean, real edges. */
internal fun darkFlyerJpeg(): ByteArray =
    testJpeg { g ->
        g.color = Color(4, 4, 6)
        g.fillRect(0, 0, 192, 108)
        g.color = Color(70, 60, 80)
        g.fillRect(20, 30, 40, 60)
        g.fillRect(120, 20, 30, 70)
        g.drawString("SHADOW", 60, 100)
    }

class DerivativeUniformityTest {
    @Test
    fun `an all-black frame is below the threshold`() {
        DerivativeUniformity.luminanceSpread(flatJpeg(Color.BLACK)).shouldNotBeNull() shouldBeLessThan DerivativeUniformity.BLANK_SPREAD
    }

    @Test
    fun `one flat colour is below the threshold whatever its brightness`() {
        // Gretchen's Des Rocs derivative is flat grey at mean 44, not black.
        DerivativeUniformity.luminanceSpread(flatJpeg(Color(44, 44, 52))).shouldNotBeNull() shouldBeLessThan DerivativeUniformity.BLANK_SPREAD
        DerivativeUniformity.luminanceSpread(flatJpeg(Color.WHITE)).shouldNotBeNull() shouldBeLessThan DerivativeUniformity.BLANK_SPREAD
    }

    @Test
    fun `a dark flyer with real content is above the threshold`() {
        DerivativeUniformity.luminanceSpread(darkFlyerJpeg()).shouldNotBeNull() shouldBeGreaterThan DerivativeUniformity.BLANK_SPREAD
    }

    @Test
    fun `bytes the JVM cannot decode measure nothing`() {
        DerivativeUniformity.luminanceSpread(byteArrayOf(9, 9)).shouldBeNull()
    }
}
