package de.norm.events.scraper

import java.net.URI

// Shared URL builders for the Gatsby sites this project scrapes (Insel, Zenner). Gatsby
// publishes each page's GraphQL result as a JSON artefact beside the page, which is a strict
// machine-readable source (ADR-007 §"Prefer a JSON / API Source") rather than the rendered
// markup. Only the artefact locations live here; every venue keeps its own field mapping.

/**
 * Maps a Gatsby page URL onto its page-data artefact:
 * `https://zenner.berlin/programm` → `https://zenner.berlin/page-data/programm/page-data.json`.
 *
 * Gatsby keys the artefact by the page's own path, so the path is taken verbatim (minus
 * surrounding slashes) and slotted into the fixed `/page-data/<path>/page-data.json` layout. The
 * site root (`/`) is Gatsby's `index` page and is named as such.
 */
fun gatsbyPageDataUrl(pageUrl: String): String {
    val uri = URI.create(pageUrl)
    val path =
        uri.path
            .orEmpty()
            .trim('/')
            .ifBlank { ROOT_PAGE_NAME }
    return uri.resolve("/$PAGE_DATA_SEGMENT/$path/$PAGE_DATA_FILE").toString()
}

/**
 * Maps a static-query hash onto its artefact:
 * `3497155224` → `https://www.inselberlin.de/page-data/sq/d/3497155224.json`.
 */
fun gatsbyStaticQueryUrl(
    pageUrl: String,
    hash: String
): String = URI.create(pageUrl).resolve("/$PAGE_DATA_SEGMENT/$STATIC_QUERY_SEGMENT/$hash.json").toString()

/** The page-data key listing the static queries a page depends on. */
const val GATSBY_STATIC_QUERY_HASHES = "staticQueryHashes"

/** Directory Gatsby publishes its query results under. */
private const val PAGE_DATA_SEGMENT = "page-data"

/** Sub-directory Gatsby publishes static-query results under, keyed by query hash. */
private const val STATIC_QUERY_SEGMENT = "sq/d"

/** Filename of a Gatsby page-data artefact. */
private const val PAGE_DATA_FILE = "page-data.json"

/** Gatsby's name for the site root's page-data directory. */
private const val ROOT_PAGE_NAME = "index"
