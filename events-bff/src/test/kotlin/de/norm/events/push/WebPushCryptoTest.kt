package de.norm.events.push

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.Signature
import java.time.Instant

/** RFC 8291 Appendix A byte for byte, and an RFC 8292 token that the public key verifies. */
class WebPushCryptoTest {
    private val asPublic = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"
    private val asPrivate = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"

    @Test
    fun `encrypts the RFC 8291 example to the published body`() {
        val ephemeral =
            KeyPair(
                WebPushCrypto.publicKey(WebPushCrypto.fromBase64Url(asPublic)),
                WebPushCrypto.privateKey(WebPushCrypto.fromBase64Url(asPrivate))
            )

        val body =
            WebPushCrypto.encrypt(
                plaintext = "When I grow up, I want to be a watermelon".toByteArray(),
                uaPublic = WebPushCrypto.fromBase64Url("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
                authSecret = WebPushCrypto.fromBase64Url("BTBZMqHH6r4Tts7J_aSIgg"),
                salt = WebPushCrypto.fromBase64Url("DGv6ra1nlYgDCS1FRnbzlw"),
                ephemeral = ephemeral
            )

        // Two strings, decoded apart: the 86-byte header does not end on a base64 boundary.
        val header = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"
        val record = "8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ"
        WebPushCrypto.base64Url(body) shouldBe WebPushCrypto.base64Url(WebPushCrypto.fromBase64Url(header) + WebPushCrypto.fromBase64Url(record))
    }

    @Test
    fun `signs a VAPID token that the public key verifies`() {
        val keys = VapidKeys.of(asPrivate, asPublic)

        val header = keys.authorization("https://fcm.googleapis.com", "mailto:hello@example.de", Instant.ofEpochSecond(1_800_000_000))

        header shouldStartWith "vapid t="
        header.substringAfter(", k=") shouldBe asPublic
        val token = header.substringAfter("t=").substringBefore(",")
        val (head, claims, signature) = token.split(".")
        String(WebPushCrypto.fromBase64Url(claims)) shouldBe """{"aud":"https://fcm.googleapis.com","exp":1800000000,"sub":"mailto:hello@example.de"}"""
        Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(WebPushCrypto.publicKey(WebPushCrypto.fromBase64Url(asPublic)))
            update("$head.$claims".toByteArray())
            verify(WebPushCrypto.fromBase64Url(signature))
        } shouldBe true
    }

    @Test
    fun `refuses a public key that does not belong to the private key`() {
        val otherPublic = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"

        shouldThrow<IllegalArgumentException> { VapidKeys.of(asPrivate, otherPublic) }
    }

    @Test
    fun `a payload fits while the whole body stays within 4096 bytes`() {
        val browser = WebPushCrypto.fromBase64Url(asPublic)

        WebPushCrypto.encrypt(ByteArray(3993), browser, ByteArray(16)).size shouldBe 4096
        shouldThrow<IllegalArgumentException> { WebPushCrypto.encrypt(ByteArray(3994), browser, ByteArray(16)) }
    }

    @Test
    fun `a random encryption has the header layout and grows by the record overhead`() {
        val plaintext = ByteArray(100)

        val body = WebPushCrypto.encrypt(plaintext, WebPushCrypto.fromBase64Url(asPublic), ByteArray(16))

        // 16 salt + 4 record size + 1 key length + 65 key, then the record: plaintext, delimiter, 16-byte tag.
        body.size shouldBe 86 + 100 + 1 + 16
        body[20] shouldBe 65.toByte()
    }
}
