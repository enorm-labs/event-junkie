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
scripts/k3d-rehearsal.sh all      # up → verify → import → chain → test → down
```

Use the individual commands when something fails and you want to keep the cluster to look at it:

```bash
scripts/k3d-rehearsal.sh up       # build, create, install
scripts/k3d-rehearsal.sh verify   # ingress routing, positive and negative
scripts/k3d-rehearsal.sh import   # seed one source, run a real import
scripts/k3d-rehearsal.sh chain    # the acceptance criterion
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

- **`k3d` missing** — `brew install k3d`. `kubectl`, `helm`, `docker` and `yq` are assumed.
- **CoreDNS needs a nudge, and the script gives it one.** k3d writes `host.k3d.internal` into the CoreDNS ConfigMap during cluster creation, but the `reload`
  plugin only picks it up on its next poll — up to 30 seconds later. Installing inside that window gives every pod `UnknownHostException: host.k3d.internal`,
  and the importer crash-loops until DNS catches up. It self-heals, which is _worse_ than failing: the install still succeeds and the only evidence is a restart
  count nobody reads. The script forces the reload and waits for it. This was found by running the script rather than the same steps by hand — doing it manually
  was slow enough to never hit the race, which is a good reminder that a scripted sequence is not just a faster human.
- **The rehearsal uses its own database** (`event_junkie_k3d`), never the development one. Installing the chart runs Flyway; pointing that at `event_junkie`
  would have the in-cluster importer fighting a local `bootRun` over one schema, and re-seeding means re-scraping ~86 sources.
- **Port 8080 must be free on the host** — that is where Traefik is published, and it is also the BFF's local `bootRun` port. Stop `dev-env.sh` first.
- **The first install of a release is not the interesting case for probes.** The BFF reports Ready before the schema exists (#438), so a green `up` does not mean
  the readiness probe is meaningful. Do not read more into it than it says.
- **"Runs on k3d" is not "runs on Hetzner".** No TLS, no cert-manager, no DNS, no NetworkPolicies, no Flux, one node under no load, and a database on the same
  machine. Report it in those terms — `deploy/AGENTS.md` uses the phrase "installed and exercised locally" and means it.
