package de.norm.events.common

import java.security.MessageDigest
import java.util.HexFormat

/**
 * A weak entity tag for a rendered body, so a polling client gets a `304`. Weak, because the server
 * compresses for some clients only and the bytes differ.
 */
object WeakETag {
    private const val TAG_BYTES = 16

    fun of(body: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
        return "W/\"${HexFormat.of().formatHex(digest, 0, TAG_BYTES)}\""
    }
}
