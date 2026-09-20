package de.norm.events.image

import org.springframework.modulith.ApplicationModule

/**
 * Serving of the venue images the importer cached (ADR-019): until something serves the copy in
 * our bucket, the browser still fetches the venue's, which is why this module reaches object
 * storage where nothing else in the BFF leaves the cluster. `event` is deliberately absent, as
 * in [de.norm.events.sourcelicence]: this answers a question for a whole page and the caller
 * owns the response, which keeps the edge one-way. The tables belong to the importer (ADR-005);
 * what is read here are lean projections.
 */
@ApplicationModule(allowedDependencies = [])
class ImageModule
