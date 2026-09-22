# Architecture inventories

Each `*.inventory.txt` file here lists what one environment deploys. The file is generated. Do not edit it by hand.

```sh
scripts/architecture-diagram.sh check       # CI runs this — it fails when a file here is out of date
scripts/architecture-diagram.sh write       # rewrite all of them after an intended chart change
scripts/architecture-diagram.sh render      # the picture, into build/architecture/ — not committed
```

## What these files are for

The diagrams that explain the platform are in [ops/PLATFORM_SETUP.md](../ops/PLATFORM_SETUP.md). A person writes those diagrams. A person must also correct
them. These inventories are the mechanism that asks for the correction.

`scripts/architecture-diagram.sh` renders the Helm chart with each cluster's own HelmRelease values. It prints the result as sorted text. CI compares that text
to the file committed here. A difference fails the build. The diff names the resource that appeared or disappeared. That is the signal to read the hand-written
diagram again.

[ADR-034](../adr/ADR-034_ARCHITECTURE_DIAGRAMS.md) records why the generated picture is not committed.

## What the files leave out

- **Image tags.** Every tag comes from `.Chart.AppVersion`. A release would rewrite all three files and the gate would fail at each version bump.
  `scripts/cluster-assertions.sh` asserts that no HelmRelease pins a tag.
- **Values that the chart does not turn into a resource.** The chart's own test suites assert those.
- **Anything outside the chart.** Traefik, cert-manager, Flux, OpenObserve and PostgreSQL are in the cluster. This chart does not create them.

## When a file changes

1. Read the diff. It says which resource changed.
2. Run `scripts/architecture-diagram.sh write` if the change is intended.
3. Open [ops/PLATFORM_SETUP.md](../ops/PLATFORM_SETUP.md) §1.4 and check that the diagram is still true.
4. Commit the inventory with the chart change. The two belong in one commit.
