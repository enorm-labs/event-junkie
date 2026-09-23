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
    val address: String? = null,
    val city: String = "Berlin",
    val postalCode: String? = null,
    /** Berlin borough (Bezirk) as a canonical slug. Example: `"friedrichshain-kreuzberg"` */
    val district: String? = null,
    /** Geographic latitude for map display. Example: `52.507242` */
    val latitude: BigDecimal? = null,
    /** Geographic longitude for map display. Example: `13.451803` */
    val longitude: BigDecimal? = null,
    val websiteUrl: String? = null,
    /** URL of the venue's logo or photo. */
    val imageUrl: String? = null,
    /** Who to credit for [imageUrl], worded as the archive publishes it. Null exactly when [imageUrl] is. */
    val imageAttribution: String? = null,
    /** SPDX identifier of the licence [imageUrl] is published under. Example: `"CC-BY-SA-4.0"` */
    val imageLicenceId: String? = null,
    /** The image's description page, which the rendered credit links to. */
    val imageSourceUrl: String? = null,
    /** Short prose description of the venue, shown on the detail page. */
    val description: String? = null,
    /** Language of [description]: `de` or `en`. */
    val descriptionLanguage: String? = null,
    /** The same description in the other language, written by hand rather than translated by a machine. */
    val descriptionAlt: String? = null,
    /** Language of [descriptionAlt]: `de` or `en`. Null exactly when [descriptionAlt] is. */
    val descriptionAltLanguage: String? = null,
    /** Timestamp when this record was first created. Set by the database. */
    val createdAt: Instant? = null,
    /** Timestamp when this record was last modified. Set by the database. */
    val updatedAt: Instant? = null
)
