package de.norm.events.sitemap

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for the detail-page sitemaps (#367). Depends on nothing: it reads slugs with its
 * own queries and never touches another module's entities.
 */
@ApplicationModule(allowedDependencies = [])
class SitemapModule
