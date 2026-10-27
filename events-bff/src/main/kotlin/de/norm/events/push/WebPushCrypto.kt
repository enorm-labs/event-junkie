package de.norm.events.push

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Web Push by hand, with the JDK only: the `aes128gcm` payload encryption of RFC 8291 and the VAPID signature of RFC 8292.
 * Every key is P-256. A public key travels as its 65-byte uncompressed point, a private key as its 32-byte scalar,
 * both base64url without padding, as browsers and the push services expect.
 */
object WebPushCrypto {
    private const val RECORD_SIZE = 4096
    private const val SALT_BYTES = 16
    private const val POINT_BYTES = 65
    private const val COORDINATE_BYTES = 32
    private const val KEY_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val PADDING_DELIMITER: Byte = 2
    private const val HEADER_BYTES = SALT_BYTES + Int.SIZE_BYTES + 1 + POINT_BYTES

    /** A push service must accept a body this large (RFC 8030 §7.2); some refuse anything larger. */
    private const val MAX_BODY_BYTES = 4096
    private const val UNCOMPRESSED: Byte = 4

    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    private val curve: ECParameterSpec =
        AlgorithmParameters
            .getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)

    fun base64Url(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** Accepts padded or unpadded base64url, and whitespace, which some browsers and docs carry. */
    fun fromBase64Url(text: String): ByteArray = decoder.decode(text.filterNot(Char::isWhitespace).trimEnd('='))

    fun publicKey(point: ByteArray): ECPublicKey {
        require(point.size == POINT_BYTES && point[0] == UNCOMPRESSED) { "Not an uncompressed P-256 point" }
        val x = BigInteger(1, point.copyOfRange(1, 1 + COORDINATE_BYTES))
        val y = BigInteger(1, point.copyOfRange(1 + COORDINATE_BYTES, POINT_BYTES))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curve)) as ECPublicKey
    }

    fun privateKey(scalar: ByteArray): ECPrivateKey {
        require(scalar.size == COORDINATE_BYTES) { "Not a P-256 private key" }
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), curve)) as ECPrivateKey
    }

    fun point(key: ECPublicKey): ByteArray = byteArrayOf(UNCOMPRESSED) + unsigned(key.w.affineX) + unsigned(key.w.affineY)

    /**
     * Encrypts [plaintext] for one browser, whose public key and auth secret come from its subscription. Answers the
     * request body: the 86-byte header (salt, record size, our ephemeral public key) and one record.
     */
    fun encrypt(
        plaintext: ByteArray,
        uaPublic: ByteArray,
        authSecret: ByteArray
    ): ByteArray {
        val ephemeral = KeyPairGenerator.getInstance("EC").apply { initialize(curve, random) }.generateKeyPair()
        return encrypt(plaintext, uaPublic, authSecret, ByteArray(SALT_BYTES).also(random::nextBytes), ephemeral)
    }

    /** [encrypt] with a fixed salt and key pair, so RFC 8291's Appendix A reproduces byte for byte. */
    internal fun encrypt(
        plaintext: ByteArray,
        uaPublic: ByteArray,
        authSecret: ByteArray,
        salt: ByteArray,
        ephemeral: KeyPair
    ): ByteArray {
        // The whole body: the header, then the record of the plaintext, its delimiter and the GCM tag.
        val bodySize = HEADER_BYTES + plaintext.size + 1 + TAG_BITS / Byte.SIZE_BITS
        require(bodySize <= MAX_BODY_BYTES) { "Payload too large" }
        val asPublic = point(ephemeral.public as ECPublicKey)
        val ecdhSecret =
            KeyAgreement.getInstance("ECDH").run {
                init(ephemeral.private)
                doPhase(publicKey(uaPublic), true)
                generateSecret()
            }
        val keyInfo = "WebPush: info".toByteArray() + 0 + uaPublic + asPublic
        val ikm = expand(hmac(authSecret, ecdhSecret), keyInfo, COORDINATE_BYTES)
        val prk = hmac(salt, ikm)
        val cek = expand(prk, "Content-Encoding: aes128gcm".toByteArray() + 0, KEY_BYTES)
        val nonce = expand(prk, "Content-Encoding: nonce".toByteArray() + 0, NONCE_BYTES)
        val record =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                doFinal(plaintext + PADDING_DELIMITER)
            }
        val header =
            ByteBuffer
                .allocate(HEADER_BYTES)
                .put(salt)
                .putInt(RECORD_SIZE)
                .put(POINT_BYTES.toByte())
                .put(asPublic)
                .array()
        return header + record
    }

    private fun hmac(
        key: ByteArray,
        data: ByteArray
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** HKDF-Expand for one block, which is all Web Push needs: every output is at most 32 bytes. */
    private fun expand(
        prk: ByteArray,
        info: ByteArray,
        length: Int
    ): ByteArray = hmac(prk, info + 1).copyOf(length)

    private operator fun ByteArray.plus(byte: Int): ByteArray = this + byte.toByte()

    /** A coordinate as exactly 32 bytes: BigInteger drops leading zeros and may add a sign byte. */
    private fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().takeLast(COORDINATE_BYTES).toByteArray()
        return ByteArray(COORDINATE_BYTES - bytes.size) + bytes
    }
}

/** Our VAPID key pair (RFC 8292). The private half is a credential: a Kubernetes Secret, never the repository (ADR-055). */
class VapidKeys(
    val private: ECPrivateKey,
    val publicBase64Url: String
) {
    override fun toString(): String = "VapidKeys(public=$publicBase64Url)"

    /**
     * The `Authorization` header value for a push service at [audience] (RFC 8292): a JWT signed with our private key,
     * and our public key. [subject] is a `mailto:` or `https:` contact for the push service's operator.
     */
    fun authorization(
        audience: String,
        subject: String,
        expiresAt: Instant
    ): String {
        val header = WebPushCrypto.base64Url("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
        val claims = WebPushCrypto.base64Url("""{"aud":"$audience","exp":${expiresAt.epochSecond},"sub":"$subject"}""".toByteArray())
        val signature =
            Signature.getInstance("SHA256withECDSAinP1363Format").run {
                initSign(private)
                update("$header.$claims".toByteArray())
                sign()
            }
        return "vapid t=$header.$claims.${WebPushCrypto.base64Url(signature)}, k=$publicBase64Url"
    }

    companion object {
        /** Reads both halves as `scripts/vapid-keys.sh` prints them, and checks that they belong together. */
        fun of(
            privateBase64Url: String,
            publicBase64Url: String
        ): VapidKeys {
            val private = WebPushCrypto.privateKey(WebPushCrypto.fromBase64Url(privateBase64Url))
            val public = WebPushCrypto.publicKey(WebPushCrypto.fromBase64Url(publicBase64Url))
            val probe = "vapid".toByteArray()
            val signed =
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(private)
                    update(probe)
                    sign()
                }
            val matches =
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(public)
                    update(probe)
                    verify(signed)
                }
            require(matches) { "The VAPID public key does not belong to the private key" }
            return VapidKeys(private, WebPushCrypto.base64Url(WebPushCrypto.point(public)))
        }
    }
}
