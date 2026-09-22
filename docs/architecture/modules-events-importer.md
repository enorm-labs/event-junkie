# Modules — events-importer

The application modules and the dependencies between them, as Spring Modulith reads them.

**This diagram is generated.** Do not edit it by hand. Rewrite it with `./gradlew updateModuleDiagrams`.

<!-- generated: module diagram -->

```mermaid
flowchart TD
    artist["artist"]
    common["common «open»"]
    dataquality["dataquality"]
    event["event"]
    genretag["genretag"]
    image["image"]
    licence["licence"]
    musicbrainz["musicbrainz"]
    promoter["promoter"]
    scraper["scraper"]
    slug["slug"]
    translation["translation"]
    venue["venue"]
    wikimedia["wikimedia"]

    artist --> common
    artist --> slug
    dataquality --> event
    dataquality --> scraper
    event --> artist
    event --> common
    event --> genretag
    event --> promoter
    event --> slug
    event --> venue
    genretag --> common
    musicbrainz --> artist
    musicbrainz --> common
    promoter --> common
    promoter --> slug
    scraper --> artist
    scraper --> common
    scraper --> event
    scraper --> genretag
    scraper --> licence
    scraper --> musicbrainz
    scraper --> promoter
    scraper --> slug
    scraper --> translation
    scraper --> venue
    scraper --> wikimedia
    translation --> event
    venue --> common
    venue --> slug
    wikimedia --> common
```

<!-- /generated: module diagram -->
