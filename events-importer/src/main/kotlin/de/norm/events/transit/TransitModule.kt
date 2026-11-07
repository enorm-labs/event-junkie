package de.norm.events.transit

import org.springframework.modulith.ApplicationModule

/**
 * The stations near a venue (#2724): a quarterly import of VBB's GTFS stops into `transit_stop`, which the BFF reads.
 * It depends on `common` for the User-Agent and the cancellation helper, and on no venue code: the BFF joins the two.
 */
@ApplicationModule(allowedDependencies = ["common"])
class TransitModule
