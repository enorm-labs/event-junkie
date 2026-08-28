package de.norm.events.image

import org.springframework.modulith.ApplicationModule

/**
 * Serving of the venue images the importer cached (ADR-019).
 *
 * **This module is the whole reason the disclosure stops.** PRs 3, 4 and 4a put a copy of each venue
 * image in our bucket; until something serves it, the browser still fetches the venue's. That is
 * what this does, and it is why the module reaches object storage where nothing else in the BFF
 * leaves the cluster.
 *
 * `event` is deliberately absent, exactly as in [de.norm.events.sourcelicence]. This module answers
 * a question in one query for a whole page and returns the answer; the caller owns the response it
 * substitutes into. Keeping the edge one-way is what stops `event` and `image` forming a cycle.
 *
 * The tables belong to the importer, which owns the schema (ADR-005). What is read here are lean
 * projections over them, in the same spirit as [de.norm.events.event.EventEntity].
 */
@ApplicationModule(allowedDependencies = [])
class ImageModule
