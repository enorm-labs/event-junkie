# Modules — events-core

The application modules and the dependencies between them, as Spring Modulith reads them.

**This diagram is generated.** Do not edit it by hand. Rewrite it with `./gradlew updateModuleDiagrams`.

<!-- generated: module diagram -->

```mermaid
flowchart TD
    artist["artist"]
    event["event"]
    genretag["genretag"]
    licence["licence"]
    promoter["promoter"]
    venue["venue"]

    event --> artist
    event --> promoter
    event --> venue
```

<!-- /generated: module diagram -->
