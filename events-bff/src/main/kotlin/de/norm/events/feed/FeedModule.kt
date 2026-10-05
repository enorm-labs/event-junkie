package de.norm.events.feed

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for the feeds a client polls: the RSS feed of new events (#368) and the calendar
 * subscription (#2719). It asks `event` for the events, through the same filters and licence gate
 * as the list, and only renders them. `venue` is the type of the summary's venue, read for its name.
 */
@ApplicationModule(allowedDependencies = ["event", "venue", "common"])
class FeedModule
