package de.norm.events.sitemap

import org.springframework.modulith.ApplicationModule

/**
 * Module metadata for the detail-page sitemaps (#367). Depends only on `common`, for the site's
 * origin: it reads slugs with its own queries and never touches another module's entities.
 */
@ApplicationModule(allowedDependencies = ["common"])
class SitemapModule
