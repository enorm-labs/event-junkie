package de.norm.events.feed

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for the RSS feed of new events (#368). It asks `event` for the events, through the
 * same filters and licence gate as the list, and only renders them. `venue` is the type of the
 * summary's venue, read for its name.
 */
@ApplicationModule(allowedDependencies = ["event", "venue", "common"])
class FeedModule
