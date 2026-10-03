# Modules — events-bff

The application modules and the dependencies between them, as Spring Modulith reads them.

**This diagram is generated.** Do not edit it by hand. Rewrite it with `./gradlew updateModuleDiagrams`.

<!-- generated: module diagram -->

```mermaid
flowchart TD
    artist["artist"]
    common["common «open»"]
    event["event"]
    feed["feed"]
    genretag["genretag"]
    image["image"]
    licence["licence"]
    meta["meta"]
    promoter["promoter"]
    sitemap["sitemap"]
    sourcelicence["sourcelicence"]
    venue["venue"]

    artist --> common
    artist --> image
    event --> artist
    event --> common
    event --> genretag
    event --> image
    event --> licence
    event --> promoter
    event --> sourcelicence
    event --> venue
    feed --> common
    feed --> event
    feed --> venue
    genretag --> common
    promoter --> common
    promoter --> image
    sitemap --> common
    sourcelicence --> licence
    venue --> common
    venue --> image
```

<!-- /generated: module diagram -->
