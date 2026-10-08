package de.norm.events.importing

/**
 * Exception thrown when a generated slug conflicts with a reserved API path segment
 * (e.g. "import", "retry"). This is a client input error (400 BAD REQUEST) because
 * the source name produces a slug that would cause routing conflicts.
 */
class ReservedSlugException(
    slug: String
) : RuntimeException("Slug '$slug' is reserved and cannot be used")
