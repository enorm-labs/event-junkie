# k3d Rehearsal

Run the whole stack — the Helm chart and all three images — on a local Kubernetes, and judge whether what comes up is actually correct. This is the runtime
counterpart to [`/verify`](verify.prompt.md), which only ever renders the chart: `helm template` passing is not evidence that a pod starts, and every claim this
project makes about the deployment was static until #263 ran it for the first time.

Per [ADR-012](../../docs/adr/ADR-012_CLOUD_PLATFORM.md) the local k3d path **is** the production deployment path, not an approximation of it — the same chart and
the same images run on Hetzner k3s. That is what makes this worth the minutes it costs.

## Important

- **The mechanics live in [`scripts/k3d-rehearsal.sh`](../../scripts/k3d-rehearsal.sh); the judgement lives here.** Do not re-derive `k3d`, `helm` and `kubectl`
  incantations — the script exists for the same reason `dev-env.sh` does. Read it before changing how any step works.
- **This talks to a Kubernetes cluster, which nothing else in this repository does.** The script passes `--context k3d-event-junkie` on every call and never
  relies on the active context. Do not "simplify" that away, and do not run bare `kubectl` alongside it. See [deploy/AGENTS.md](../../deploy/AGENTS.md).
- **Never name another kubeconfig context in a commit, issue or PR.** A working machine's kubeconfig lists somebody's other clients; those names are not this
  project's to publish.
- **One import, one small venue.** The rehearsal scrapes a real website. ADR-007 politeness applies exactly as it does to `/importer-smoke`.
- **Always tear down.** `all` traps the teardown so it runs even on failure. A leftover cluster is a background process and, worse, a `k3d-*` context somebody
  later mistakes for a live one.

## The fast path

```bash
scripts/k3d-rehearsal.sh all      # up → verify → import → chain → images → test → down
```

## The image path — `K3D_IMAGES=1`

```bash
K3D_IMAGES=1 scripts/k3d-rehearsal.sh all
```

Adds [`values-k3d-images.yaml`](../../deploy/charts/event-junkie/values-k3d-images.yaml) on top of the ordinary overrides, creates the two Secrets, and points
object storage at the compose stack's MinIO. The `images` step then asserts the whole of ADR-019 and ADR-020: derivatives generated, AVIF among the formats, one
of them served through Traefik, and `imageUrl` pointing at our own origin.

It also **seeds Cassiopeia rather than AMT**, which is not cosmetic. AMT often publishes nothing upcoming, and an event that is never persisted carries no
`image_url` — so the image step skips for a reason that has nothing to do with images. It is still one import of one venue.

**Off by default**, because it needs MinIO on the host and the plain rehearsal must work without it.

**This exists because nothing exercised the path.** Three defects reached staging from one pull request: an invariant that had never rendered a second
container, a decode limit rendered in scientific notation, and a sidecar the importer was never told to call. `helm template` passed for all three. This run
fails on any of them.

Use the individual commands when something fails and you want to keep the cluster to look at it:

```bash
scripts/k3d-rehearsal.sh up       # build, create, install
scripts/k3d-rehearsal.sh verify   # ingress routing, positive and negative
scripts/k3d-rehearsal.sh import   # seed one source, run a real import
scripts/k3d-rehearsal.sh chain    # the acceptance criterion
scripts/k3d-rehearsal.sh images   # the image path, with K3D_IMAGES=1
scripts/k3d-rehearsal.sh test     # helm test
scripts/k3d-rehearsal.sh status
scripts/k3d-rehearsal.sh down     # always, eventually
```

## The other rehearsal — `flux-all` (#414)

```bash
scripts/k3d-rehearsal.sh flux-all   # flux-up → flux-verify → flux-trap → flux-break → down
```

**These two answer different questions and must not share a cluster.** `all` installs the _working
tree's_ chart with images built seconds ago — _"does my change work?"_. `flux-all` installs the chart
already **published in GHCR**, through the same controllers that will run on Hetzner — _"does the
delivery mechanism work?"_. Running both against one cluster would put two importers on one schema,
which is the exact ADR-008 failure the chart pins replicas to prevent.

|               |                                                                                       |
| ------------- | ------------------------------------------------------------------------------------- |
| `flux-up`     | cluster, `flux install`, apply `deploy/clusters/k3d`, wait for source **and** release |
| `flux-verify` | a snapshot resolved, images from GHCR, one shared tag, `helm test` passed             |
| `flux-trap`   | removes the `-0` from the semver range and watches it stop matching                   |
| `flux-break`  | breaks the release on purpose and watches it roll back                                |

**`flux-break` is the one that earns its keep**, and it has already paid: it found that
`remediateLastFailure` defaults to `false`, so a bad deploy was retried and then left running
broken. Reading the manifest would never have shown it. Deliberately **not** `flux bootstrap` — that
commits manifests to this repository and creates a deploy key, for a cluster that lives ten minutes.

## What "it worked" means

The single acceptance criterion is the **chain**: an event scraped by the in-cluster importer comes back out of `/api/events` through Traefik. Everything else
can pass with the pieces working only in isolation. If `chain` fails, the rehearsal failed, whatever the other steps said.

Beyond that, six things are worth confirming rather than assuming:

|                             |                                                                                                            |
| --------------------------- | ---------------------------------------------------------------------------------------------------------- |
| All three pods Ready        | and **with no restarts** — a pod that recovered after crashing is a different result from one that started |
| `/` and `/api/events`       | 200, and the right content types                                                                           |
| `/actuator/**` via ingress  | **the SPA fallback**, not actuator                                                                         |
| `/api/admin/**` via ingress | a 404 from the BFF, never the importer                                                                     |
| `helm test`                 | passes                                                                                                     |
| The node architecture       | on Apple Silicon this is `arm64`, so the rehearsal exercises the architecture Hetzner runs                 |

## The trap that has already caught someone

**Check the content type, not the status code**, on every negative assertion. nginx serves the SPA for any unmatched path, so `/actuator/health` through the
ingress returns **200** — and that 200 is `text/html`. A status-only test reports a leak that is not there, and would keep passing if actuator were genuinely
exposed. The script does this correctly; if you write an ad-hoc check, write it the same way.

## When it finds something

It is supposed to. #263 found two values bugs, a wrong prediction and a documented expectation that was false, in one run.

- **A chart or values defect** → fix it on the branch, then **re-run from scratch** (`down` then `up`). A fix that only works on a mutated cluster is not a fix.
- **Something that is not this branch's to fix** → [`/new-issue`](new-issue.prompt.md) rather than widening the change.
- **A claim in the docs that the run contradicts** → correct it in the same PR, and say what was measured. `deploy/charts/event-junkie/README.md` and
  `deploy/AGENTS.md` are where the chart's behavioural claims live, and both have been wrong before.

## Gotchas

- **`k3d` missing** — `brew install k3d`. `kubectl`, `helm`, `docker` and `yq` are assumed. The opt-in airgap preload below also wants `jq`, and `crane`
  (`brew install crane`) if it can have it.
- **Every pod stuck in `ContainerCreating` forever** — the node cannot pull from Docker Hub, and the real cause is four `describe`s away. On a network that
  inspects TLS this is the shape it takes: the _host_ trusts the interception CA, so the three images build without complaint and `k3d cluster create` succeeds,
  while the _node's_ containerd does not and fails on `rancher/mirrored-pause:3.6` — the sandbox image, which nothing this project owns. Nothing starts: not
  CoreDNS, not Traefik, not the workloads. It surfaces late and reads as a chart problem; the install fails on `no matches for kind "Middleware"` because
  Traefik's CRD job never ran either.

    ```sh
    K3D_PRELOAD_IMAGES=1 scripts/k3d-rehearsal.sh all
    ```

    Fetches k3s's eight system images on the host and hands them to the node as an airgap tarball. Off by default, because it costs a fetch-and-verify and
    nobody whose node can reach Docker Hub needs it. It installs no CA anywhere and must not grow into doing so — a shared script that injects a corporate
    trust root is a worse problem than the one it solves.

    **The "six of eight images land" result this used to record was a bug in the preload, not a limit of it (#533).** The reading was that `coredns` and
    `metrics-server` reached containerd's content store (`ctr -n k8s.io images ls` listed them) without becoming visible to the CRI (`crictl images` did not),
    and that kubelet therefore kept pulling and failed on the same certificate. The observations were right and the conclusion was wrong: **the tarball never
    contained those two images.** `docker save` had exited 0 having written their manifests and configs with no layer blobs at all — 12 KB each — so `ctr`
    listed what it had been given and the CRI correctly refused to. Everything downstream followed from that, including the `k3d image import` retry, which
    uses `docker save` too.

    The preload now fetches with [`crane`](https://github.com/google/go-containerregistry) rather than `docker save`, and **reads the tarball back before
    trusting it** — every layer blob its own manifest names has to be in it, or the run fails naming the images. Success ends with `8 images verified in …`;
    that word is the one to look for. `brew install crane` if it is missing, and the run will tell you.

    **What that leaves.** If the preload verifies and pods _still_ sit in `ContainerCreating` with an x509 error, that is a node which genuinely cannot pull —
    the failure this escape hatch is actually for, tracked in #526 — and not a preload that quietly did nothing. The two were indistinguishable before #533,
    which is what made the first diagnosis so convincing. On a network like that the honest options are still to run off it, or to accept that the k3d path is
    unavailable and verify against staging instead. Do not reach for `insecure_skip_verify` in a committed file.

- **CoreDNS needs a nudge, and the script now gives it one in the right order (#541).** k3d writes `host.k3d.internal` into the CoreDNS ConfigMap **after
  `k3d cluster create` returns** — measured at 7 to 11 seconds later. Until it lands, every pod resolving the database host gets
  `UnknownHostException: host.k3d.internal`, and the importer crash-loops; Flyway opens JDBC eagerly at startup, while the BFF's R2DBC pool connects lazily and
  never notices, so it reads as "the importer is flaky" rather than as a DNS problem. It self-heals, which is _worse_ than failing: the install still succeeds
  and the only evidence is a restart count nobody reads.
  The script waits for the ConfigMap to actually carry the entry, **then** restarts CoreDNS onto it. The earlier version restarted first and waited for the
  rollout, which proved the pod was Ready and nothing about what it had loaded — a restart cannot load a write that has not happened. Worth knowing if you read
  the old comment anywhere: the entry lands in the `NodeHosts` key, not `Corefile`, and is watched by the _hosts_ plugin's own `reload 15s` over a volume mount,
  not by the Corefile `reload` plugin.
- **The rehearsal uses its own database** (`event_junkie_k3d`), never the development one. Installing the chart runs Flyway; pointing that at `event_junkie`
  would have the in-cluster importer fighting a local `bootRun` over one schema, and re-seeding means re-scraping ~86 sources.
- **Port 8080 must be free on the host** — that is where Traefik is published, and it is also the BFF's local `bootRun` port. Stop `dev-env.sh` first.
- **A green `up` now says more about the probes than it used to, and it is worth knowing why.** Until #438 the BFF reported Ready before the schema existed, so a
  successful install proved nothing about readiness. Since #438 its readiness group includes the database and the `events` schema, which means `helm install
--wait` cannot return until the importer has migrated — a green `up` is now evidence that the ordering worked. The corollary is that `up` failing on a timeout
  is a plausible _importer_ failure rather than a BFF one; check the importer's Flyway logs first.
- **"Runs on k3d" is not "runs on Hetzner".** No TLS, no cert-manager, no DNS, no NetworkPolicies, no Flux, one node under no load, and a database on the same
  machine. Report it in those terms — `deploy/AGENTS.md` uses the phrase "installed and exercised locally" and means it.
