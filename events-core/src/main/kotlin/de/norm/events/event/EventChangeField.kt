package de.norm.events.event

/**
 * A field of an event whose change the event page shows: when and where it happens, and whether it
 * still does (#2725). The name is the `event_change.field` value and the API's.
 */
enum class EventChangeField {
    EVENT_DATE,
    START_TIME,
    END_DATE,
    END_TIME,
    STATUS,

    /** The value is the venue's id in `event_change`, and its name in the API. */
    VENUE
}
