package de.norm.events.account

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/**
 * The secrets behind a sign-in link and a session cookie: 32 random bytes, URL-safe. The database
 * holds only [hash], so a read of it cannot sign anyone in (ADR-053).
 */
internal object SecretTokens {
    private const val BYTES = 32
    private val random = SecureRandom()

    fun next(): String = ByteArray(BYTES).also(random::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    fun hash(token: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
}
