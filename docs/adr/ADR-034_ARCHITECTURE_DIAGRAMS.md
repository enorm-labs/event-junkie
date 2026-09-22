# ADR-034: Architecture diagrams are hand-written, and a generated inventory keeps them honest

## Status

**Accepted (2026-09-22) — a diagram that a reader learns from is written by hand, in Mermaid, inside the document it belongs to. A generator produces an
inventory of what each environment deploys, as sorted text under `docs/architecture/`. CI compares that text to the committed copy. The generated picture is
rendered on demand and is never committed.**

**Does not supersede anything.** [ADR-016](ADR-016_GITOPS_DELIVERY.md) decided how a change reaches a cluster. This decides how that arrangement is drawn.

## Context

[#467][467] asks for two things. A reader must see the platform in one picture. The picture must not go quietly wrong. The second half is the hard one. A
diagram that was correct when it was drawn, and is wrong a month later, is worse than no diagram. A reader trusts it by then.

`docs/ops/PLATFORM_SETUP.md` §1.1 to §1.3 already carry hand-written Mermaid diagrams of the trust boundaries, the access paths and the deploy path. §1.4
listed the workloads in two tables and showed no picture. `README.md` linked no diagram at all.

## Candidate options

1. **KubeDiagrams, with the rendered picture committed.** It reads manifests, Kustomize overlays, Helm charts and a live cluster. It renders SVG, PNG, Mermaid,
   D2 and draw.io.
2. **`tofu graph`, for the platform layer.**
3. **inframap**, which prunes an OpenTofu graph to the resources that matter.
4. **Hand-written Mermaid alone**, with a checklist item asking the author to keep it true.
5. **Hand-written Mermaid, plus a generated inventory as the gate.**

## Decision

**Option 5.** Each part does the job it is good at.

| Part                                     | Job                                                     | Committed                    |
| ---------------------------------------- | ------------------------------------------------------- | ---------------------------- |
| Mermaid in `PLATFORM_SETUP.md`           | Intent — what runs where, and why it is shaped that way | Yes. Text, renders on GitHub |
| `docs/architecture/<env>.inventory.txt`  | The set of objects each environment deploys             | Yes. One file per cluster    |
| `scripts/architecture-diagram.sh render` | The picture, for a person looking at a change           | No. Lands in `build/`        |

`scripts/architecture-diagram.sh check` runs in `validate-chart.yml`. It renders the chart with each cluster's HelmRelease values and compares the result to
the committed inventory. A difference fails the build and prints the diff. The diff names the resource. The author then reads the hand-written diagram and
decides whether it is still true.

## Why the others lost

Each number below was measured on this repository on 2026-09-22.

- **A committed rendered picture cannot be the gate, because KubeDiagrams is not deterministic.** Every node carries a random 32-character identifier, and the
  identifier changes on each run. Two runs over one input produced 912 different SVG lines and 482 different Mermaid lines. Nothing else differed. A byte
  comparison therefore reports drift on every run.
- **Neither output format is a good thing to commit.** An SVG with embedded icons is 848 KB for one environment. The Mermaid output loads its icons from
  `raw.githubusercontent.com` and uses the Mermaid 11 image shape. It needs a request to a third party and may not render on GitHub.
- **`tofu graph` needs the state credential.** On `infra/environments/staging` it stops with `Error: Backend initialization required`. A gate must hold no such
  credential. On `infra/modules/environment`, where it runs without one, it drew 229 edges into an image 12921 pixels wide. Every variable and local value is a
  node. It is a dependency graph and not an architecture picture.
- **inframap prunes that graph well, but reads state or HCL for the same platform layer that changes twice a year.** The platform diagram is four resources per
  environment. A person draws it faster than a tool, and the drawing says why.
- **A checklist item does not survive a busy week.** [#467][467] says this. The repository agrees. Every other invariant here is a script that fails a build.

**What the generated picture is still for.** `scripts/architecture-diagram.sh render` draws it in seconds, and it is a good way to read a change to
`deploy/`. KubeDiagrams also reads a live cluster, which answers a different question — what is deployed now, rather than what this tree renders. That reaches
a cluster, so it stays a command an operator types.

## Consequences

- **Positive.** A chart change that adds or removes a resource fails CI until a person looks at it. The diff is text, and it names the resource. The
  environments can also be compared with `diff docs/architecture/staging.inventory.txt docs/architecture/production.inventory.txt`, which is the clearest
  statement of how the two differ.
- **Positive.** The gate needs `helm` and `yq` only. Both are already required by `scripts/cluster-assertions.sh`. KubeDiagrams stays out of CI.
- **Negative.** A chart change now touches three more files. `scripts/architecture-diagram.sh write` rewrites them in one command.
- **Negative.** The inventory proves that the shape changed. It cannot prove that the hand-written diagram is right. Nothing can. It asks the question at the
  moment when the answer is cheap.
- **Image tags are left out**, because each one comes from `.Chart.AppVersion`. Including them would fail the gate at every release.

## References

- [#467][467] — the issue, including the expectation that a generator gives inventory and not intent
- [`scripts/architecture-diagram.sh`](../../scripts/architecture-diagram.sh) · [`docs/architecture/README.md`](../architecture/README.md)
- [`docs/ops/PLATFORM_SETUP.md`](../ops/PLATFORM_SETUP.md) §1.1 to §1.4 — the hand-written diagrams
- [KubeDiagrams](https://github.com/philippemerle/KubeDiagrams) · [inframap](https://github.com/cycloidio/inframap)
- [ADR-016](ADR-016_GITOPS_DELIVERY.md)

[467]: https://github.com/enorm-labs/event-junkie/issues/467
