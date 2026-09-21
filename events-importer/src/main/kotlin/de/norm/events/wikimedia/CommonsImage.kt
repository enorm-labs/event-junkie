package de.norm.events.wikimedia

import org.jsoup.Jsoup

/**
 * One Commons file as its `imageinfo` states it, before any rule decides whether it may be stored.
 *
 * [thumbUrl] is the rendering Commons served for the width asked, with the `utm_` query it appends
 * dropped: the column holds the identity of an image, not a campaign the fetcher would replay.
 * [servedOriginal] is true when that rendering is the original file, which Commons does where the
 * width asked meets the file's own; it is the one path that can hand back something over the
 * fetcher's cap, so [size] is checked only then.
 */
data class CommonsImage(
    val thumbUrl: String,
    /** Commons' licence template name, `CC BY-SA 4.0`; [CommonsLicences] maps it to SPDX. */
    val licenceShortName: String?,
    /** The `Artist` field, HTML with a link in it; [CommonsCredit.plain] makes a credit of it. */
    val artistHtml: String?,
    /** The file's description page, which the rendered credit links to. */
    val descriptionUrl: String?,
    val size: Long,
    val mime: String?,
    val servedOriginal: Boolean
)

/**
 * Commons publishes a licence as a template name; `image_licence_id` holds an SPDX identifier. The
 * map is written out rather than derived: a pattern over `CC BY-…` also produces an identifier for
 * a template Creative Commons never published, and a wrong licence is worse than a refused image.
 */
object CommonsLicences {
    private val spdx =
        mapOf(
            "CC0" to "CC0-1.0",
            "CC BY 2.0" to "CC-BY-2.0",
            "CC BY 2.5" to "CC-BY-2.5",
            "CC BY 3.0" to "CC-BY-3.0",
            "CC BY 3.0 de" to "CC-BY-3.0-DE",
            "CC BY 4.0" to "CC-BY-4.0",
            "CC BY-SA 2.0" to "CC-BY-SA-2.0",
            "CC BY-SA 2.0 de" to "CC-BY-SA-2.0-DE",
            "CC BY-SA 2.5" to "CC-BY-SA-2.5",
            "CC BY-SA 3.0" to "CC-BY-SA-3.0",
            "CC BY-SA 3.0 de" to "CC-BY-SA-3.0-DE",
            "CC BY-SA 4.0" to "CC-BY-SA-4.0",
            "Public domain" to "PD",
            "Public Domain Mark" to "PD",
            // Two templates SPDX does not name the way Commons does: the Free Art License, and the
            // bare Attribution template, which takes the `LicenseRef-` form SPDX publishes for it (#1281).
            "FAL" to "LAL-1.3",
            "Attribution" to "LicenseRef-Commons-Attribution"
        )

    /** The SPDX identifier for a Commons template name, or null for one the map does not know. */
    fun spdxOf(shortName: String?): String? = shortName?.let { spdx[it] }
}

/** Turns Commons' `Artist` HTML into the credit `image_attribution` holds, and says when it names nobody. */
object CommonsCredit {
    /** A label, not part of the name: `Photo: Andreas Praefcke` credits Andreas Praefcke. */
    private val label = Regex("^(photo|foto|bild|image)\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE)

    /**
     * Exactly the one phrase Commons writes when a file has no author, and no guesses beside it:
     * `Uploaded` reads like boilerplate and is a real photographer's Flickr handle.
     */
    private const val NOT_AN_AUTHOR = "no machine-readable author"

    /** The text of the `Artist` field: tags stripped, entities decoded, a `Photo:` label dropped. */
    fun plain(artistHtml: String?): String =
        Jsoup
            .parse(artistHtml.orEmpty())
            .text()
            .replace(label, "")
            .trim()

    /** False for an empty credit and for Commons' no-author boilerplate. */
    fun namesAnAuthor(credit: String): Boolean = credit.isNotEmpty() && !credit.lowercase().contains(NOT_AN_AUTHOR)
}
