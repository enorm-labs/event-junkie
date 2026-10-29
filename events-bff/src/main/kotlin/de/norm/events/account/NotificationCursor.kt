package de.norm.events.account

import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

/**
 * Where the next page of notifications starts (#4169): the sort key of the last row a page showed. A client treats the
 * token as opaque. It only positions the list, so a token made by hand still reads nothing but the session's own rows.
 */
data class NotificationCursor(
    val createdAt: Instant,
    val eventDate: LocalDate,
    val id: UUID
) {
    fun token(): String = ENCODER.encodeToString(listOf(createdAt, eventDate, id).joinToString(SEPARATOR).toByteArray())

    companion object {
        private const val SEPARATOR = "|"
        private const val PARTS = 3
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()

        fun of(notification: Notification) = NotificationCursor(notification.createdAt, notification.date, notification.id)

        /** Answers null for anything [token] did not make. */
        fun parse(token: String): NotificationCursor? =
            runCatching {
                val (createdAt, eventDate, id) = String(Base64.getUrlDecoder().decode(token)).split(SEPARATOR).also { require(it.size == PARTS) }
                NotificationCursor(Instant.parse(createdAt), LocalDate.parse(eventDate), UUID.fromString(id))
            }.getOrNull()
    }
}
