package de.norm.events

/**
 * Whether [path] is the actuator [basePath] or under it, never a route that merely starts the same:
 * a prefix comparison would also match `/actuatorial`. [CacheControlFilter] and
 * [RequestLoggingFilter] both leave these requests alone.
 */
fun isActuatorPath(
    path: String,
    basePath: String
): Boolean {
    val base = basePath.removeSuffix("/")
    return path == base || path.startsWith("$base/")
}
