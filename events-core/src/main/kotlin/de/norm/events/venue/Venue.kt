package de.norm.events.venue

import java.math.BigDecimal
import java.time.Instant

/**
 * Represents a physical venue where music events take place.
 *
 * Each venue maps to a real-world location (e.g. "Astra Kulturhaus") and
 * serves as the anchor for importing events from that venue's website.
 */
data class Venue(
    /** Database primary key, `null` before persistence. */
    val id: Long? = null,
    /** Display name of the venue. */
    val name: String,
    /** URL-friendly identifier, derived from the name. Example: `"astra-kulturhaus"` */
    val slug: String,
    /** Street address of the venue. */
    val address: String? = null,
    /** City where the venue is located. */
    val city: String = "Berlin",
    /** Postal code of the venue's address. */
    val postalCode: String? = null,
    /** Berlin borough (Bezirk) as a canonical slug. Example: `"friedrichshain-kreuzberg"` */
    val district: String? = null,
    /** Geographic latitude for map display. Example: `52.507242` */
    val latitude: BigDecimal? = null,
    /** Geographic longitude for map display. Example: `13.451803` */
    val longitude: BigDecimal? = null,
    /** URL of the venue's official website. */
    val websiteUrl: String? = null,
    /** URL of the venue's logo or photo. */
    val imageUrl: String? = null,
    /** Short prose description of the venue, shown on the detail page. */
    val description: String? = null,
    /** Timestamp when this record was first created. Set by the database. */
    val createdAt: Instant? = null,
    /** Timestamp when this record was last modified. Set by the database. */
    val updatedAt: Instant? = null
)
