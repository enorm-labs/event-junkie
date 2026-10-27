package de.norm.events.common

/**
 * The `@Schema` descriptions and examples for the credit a venue, artist or promoter image carries
 * (#1275), in the BFF's responses and the importer's requests and responses alike, so the two APIs
 * word each field once. From the row, not the served file: resizing does not change who took the
 * photograph.
 */
const val IMAGE_ATTRIBUTION_DESCRIPTION =
    "Who to credit for `imageUrl`, worded as the archive publishes it. Present whenever " +
        "`imageUrl` is, and it must be shown beside the image."

/** @see IMAGE_ATTRIBUTION_DESCRIPTION */
const val IMAGE_ATTRIBUTION_EXAMPLE = "Photographer Name, via Wikimedia Commons"

/** @see IMAGE_ATTRIBUTION_DESCRIPTION */
const val IMAGE_LICENCE_ID_DESCRIPTION =
    "SPDX identifier of the licence `imageUrl` is published under, or `PD` where no " +
        "identifier applies. Example values: `CC0-1.0`, `CC-BY-4.0`, `CC-BY-SA-4.0`."

/** @see IMAGE_ATTRIBUTION_DESCRIPTION */
const val IMAGE_LICENCE_ID_EXAMPLE = "CC-BY-SA-4.0"

/** @see IMAGE_ATTRIBUTION_DESCRIPTION */
const val IMAGE_SOURCE_URL_DESCRIPTION = "The image's description page, which the rendered credit links to"

/** @see IMAGE_ATTRIBUTION_DESCRIPTION */
const val IMAGE_SOURCE_URL_EXAMPLE = "https://commons.wikimedia.org/wiki/File:Example.jpg"
