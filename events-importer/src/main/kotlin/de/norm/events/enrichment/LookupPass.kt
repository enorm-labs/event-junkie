package de.norm.events.enrichment

/** What one lookup pass of an [ArtistLookupSweep] tick owed and stored; [OFF] for a switched-off lookup. */
data class LookupPass(
    val owed: Int,
    val stored: Int
) {
    override fun toString() = if (owed < 0) "off" else "$stored of $owed"

    companion object {
        val OFF = LookupPass(owed = -1, stored = -1)
    }
}
