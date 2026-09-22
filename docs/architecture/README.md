# Architecture

Everything here is generated. Do not edit any of it by hand. Two kinds of file, one per layer.

| File                  | Shows                                                                 | Regenerate with                         |
| --------------------- | --------------------------------------------------------------------- | --------------------------------------- |
| `*.inventory.txt`     | What one environment deploys                                          | `scripts/architecture-diagram.sh write` |
| `modules-events-*.md` | The application modules of one backend module, and their dependencies | `./gradlew updateModuleDiagrams`        |

```sh
scripts/architecture-diagram.sh check       # CI runs this — it fails when an inventory is out of date
scripts/architecture-diagram.sh write       # rewrite all of them after an intended chart change
scripts/architecture-diagram.sh render      # the picture, into build/architecture/ — not committed
./gradlew updateModuleDiagrams              # rewrite the three module diagrams
```

## The module diagrams

[modules-events-core.md](modules-events-core.md) · [modules-events-bff.md](modules-events-bff.md) ·
[modules-events-importer.md](modules-events-importer.md)

Spring Modulith reads each direct sub-package of `de.norm.events` as an application module ([ADR-006](../adr/ADR-006_SPRING_MODULITH.md)). `ModuleDiagram`, in
`events-core`'s test fixtures, draws the modules and the dependencies **the code actually has**. The declared `allowedDependencies` can be wider. The
`ModularityTests` in each module already fails when the actual set escapes the declared one, so the narrower of the two is the true one.

**There are three documents because there are three module structures, and because CI runs `./gradlew test --parallel`.** The three test tasks write at the
same time. One shared document would be a race, and the loser of the race would win the file.

Spring Modulith's own `Documenter` also runs on every test. Its output is PlantUML, which GitHub does not render, so it stays in
`<module>/build/spring-modulith-docs/`.

## The inventories

### What the inventories are for

The diagrams that explain the platform are in [ops/PLATFORM_SETUP.md](../ops/PLATFORM_SETUP.md). A person writes those diagrams. A person must also correct
them. These inventories are the mechanism that asks for the correction.

`scripts/architecture-diagram.sh` renders the Helm chart with each cluster's own HelmRelease values. It prints the result as sorted text. CI compares that text
to the file committed here. A difference fails the build. The diff names the resource that appeared or disappeared. That is the signal to read the hand-written
diagram again.

[ADR-034](../adr/ADR-034_ARCHITECTURE_DIAGRAMS.md) records why the generated picture is not committed.

### What the inventories leave out

- **Image tags.** Every tag comes from `.Chart.AppVersion`. A release would rewrite all three files and the gate would fail at each version bump.
  `scripts/cluster-assertions.sh` asserts that no HelmRelease pins a tag.
- **Values that the chart does not turn into a resource.** The chart's own test suites assert those.
- **Anything outside the chart.** Traefik, cert-manager, Flux, OpenObserve and PostgreSQL are in the cluster. This chart does not create them.

### When an inventory changes

1. Read the diff. It says which resource changed.
2. Run `scripts/architecture-diagram.sh write` if the change is intended.
3. Open [ops/PLATFORM_SETUP.md](../ops/PLATFORM_SETUP.md) §1.4 and check that the diagram is still true.
4. Commit the inventory with the chart change. The two belong in one commit.
