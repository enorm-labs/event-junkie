package de.norm.events.instagram

/** The handle in a venue's `instagram_url`, or null for a URL that names no profile. */
object InstagramHandle {
    private val PROFILE = Regex("^https?://(?:www\\.)?instagram\\.com/([A-Za-z0-9._]{1,30})/?(?:\\?.*)?$")

    /** Paths that are pages of Instagram itself, not profiles. */
    private val RESERVED = setOf("p", "reel", "reels", "explore", "stories", "accounts")

    fun of(instagramUrl: String?): String? =
        instagramUrl
            ?.trim()
            ?.let { PROFILE.matchEntire(it) }
            ?.groupValues
            ?.get(1)
            ?.takeUnless { it.lowercase() in RESERVED }
}
