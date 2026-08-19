# ADR-015: Observability Stack

## Status

**Accepted (2026-08-10) — OpenObserve, single-node, backed by Hetzner Object Storage.**

**Accepted on trial, deliberately.** This is the youngest product in the comparison and the only one carrying a copyleft licence, so it is adopted to be _used
and judged_, not to be settled forever. What makes that safe rather than woolly is that both applications emit vendor-neutral OpenTelemetry and
Prometheus-format metrics regardless of backend — so replacing it is a Helm release and a datasource, **not** a re-instrumentation. The exit was designed in
before the entry.

**Judged against these, at the point staging has been running it for a fortnight and again before go-live** — vague trials never conclude, so this is what
"does it work for us" means:

|     | Test                                    | Fails if                                                                                                                                                |
| --- | --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | **The zero-events alert fires**         | A source that silently stops importing does not page anyone. This is the requirement the whole ADR turns on — if it cannot do this, nothing else counts |
| 2   | **Footprint is roughly as claimed**     | Sustained resident memory over ~1.5 GB, which eats the CX33 headroom that [PLATFORM_SETUP.md](../ops/PLATFORM_SETUP.md) §1 depends on                   |
| 3   | **Log search is usable under pressure** | Answering "what happened to venue X on run Y at 23:00" takes longer than reading the raw pod logs would have                                            |
| 4   | **Dashboards carry business metrics**   | The importer meters in PLATFORM_SETUP.md §7 cannot be charted the way they need to be                                                                   |
| 5   | **Upgrades are uneventful**             | A minor version bump loses data or needs manual migration                                                                                               |

### Trial results so far — measured 2026-08-19 on a k3d rehearsal (#271)

Two of the five are answered, by running the thing rather than by reading its documentation. The
other three need the collector, which is not deployed yet.

| #   | Test                              | Result                                                                                                                                |
| --- | --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Zero-events alert fires           | **Open** — needs the collector and an alert rule                                                                                      |
| 2   | Footprint ≤ ~1.5 GB sustained     | **Passes.** 309Mi idle, **321Mi after ingesting 100,000 records**, against a deliberately tight 1536Mi limit. No restarts, no OOMKill |
| 3   | Log search usable under pressure  | **Passes.** `GROUP BY source` across 100,000 rows in **14ms**, 19 MB scanned; a full-text `match_all` in the same 14ms                |
| 4   | Dashboards carry business metrics | **Open** — the meters exist (#415) but nothing collects them yet                                                                      |
| 5   | Upgrades uneventful               | **Open** — needs a version bump against real data                                                                                     |

**Two things the rehearsal changed about how this gets deployed, both worth having in the ADR rather
than only in a pull request:**

- **It is the `openobserve-standalone` chart, not `openobserve`.** The plain chart deploys
  microservices — ingester, querier, router, scheduler, compactor, and `o2ai` at two replicas — which
  is nothing like the footprint this ADR compared. `openobserve-standalone` (`ZO_LOCAL_MODE: "true"`)
  is one StatefulSet and one PVC, and is what every number above was measured on. **Choosing the
  wrong one silently invalidates criterion 2**, which is the criterion that decided this ADR.
- **The ~1 GB comparison did not include the collector.** Getting Prometheus metrics in needs
  `openobserve-collector`, an OpenTelemetry Collector requiring the otel-operator, an agent DaemonSet
  and a gateway. That is real additional footprint absent from the comparison table below. It does
  not overturn the decision — 321Mi leaves a great deal of room under 1.5 GB — but **the budget has
  to be re-checked once it is deployed rather than assumed**, and criterion 2 is not truly settled
  until it is.

**What the measurement does not cover, stated so it is not read as more than it is:** k3d on arm64,
not the Hetzner x86 node; 100,000 synthetic records is a burst rather than the fortnight this section
asks for, so "sustained" is only partly exercised; and local disk throughout, with no S3 backend
involved.

**If it fails, take fallback 1 — VictoriaMetrics + VictoriaLogs + Grafana.** That is a decision already made below, not one to re-open, and it costs a Helm
change plus rebuilding dashboards.

Proposed and accepted the same day, which is unusual and worth saying plainly: the comparison was requested, done, and the answer taken as a trial with a
written exit, rather than left Proposed to rot.

> Requested while planning [#260](https://github.com/enorm-labs/event-junkie/issues/260) and the go-live sequence. This ADR picks **where logs, metrics and
> alerts live**; the instrumentation inside the applications (structured logging, Micrometer meters) is described in
> [PLATFORM_SETUP.md](../ops/PLATFORM_SETUP.md) §7 and does not depend on which backend wins — that is the point of choosing an OpenTelemetry-compatible one.
>
> **This decision is constrained by a box, not by taste.** [ADR-012](ADR-012_CLOUD_PLATFORM.md) put everything on one Hetzner node. Every gigabyte the
> observability stack takes is a gigabyte the application cannot have, and the difference between the candidates here is a factor of six. That is the whole
> argument.

## Context

[ADR-012](ADR-012_CLOUD_PLATFORM.md) chose Hetzner + k3s and recorded, in its own Consequences, that _"observability is now our problem"_ — there is no
CloudWatch, no Cloud Operations, no included anything. Its amendment then removed Cloudflare, which also removed the free edge-level traffic analytics that
would have covered part of this by accident.

So this is not an optional enhancement. It is the difference between "the site is down and I found out from a friend" and "the site is down and I was paged".
ADR-012 is explicit: _"Alerting must exist before launch, not after the first outage."_

### What has to be observed

| Source            | What it produces                                 | Why it matters here                                                 |
| ----------------- | ------------------------------------------------ | ------------------------------------------------------------------- |
| `events-importer` | Scheduled job outcomes, per-venue scrape results | **The most important signal in the system** — see below             |
| `events-bff`      | HTTP request rate, latency, error rate           | The public surface; the thing users notice                          |
| `events-frontend` | nginx access logs                                | Low value on its own, high value correlated with BFF errors         |
| PostgreSQL        | Connection count, slow queries, disk usage       | The only stateful thing; disk-full is an outage with data loss risk |
| k3s / node        | CPU, memory, disk, pod restarts, OOMKills        | On a single node, memory pressure is the failure mode               |

**The importer is the unusual requirement, and it drives the choice more than the others.** A scraper does not fail loudly. When a venue redesigns its site, the
importer keeps running, reports success, and silently writes zero events — and nobody notices for a fortnight because the site still shows last month's
listings. HTTP-level monitoring cannot see this. What catches it is a **business metric with an alert**: _"source X has imported 0 events for 3 consecutive
runs"_. Any candidate that cannot express that is disqualified regardless of how good its infrastructure dashboards look.

### Criteria

Weights reflect the single-node constraint more than anything else:

| #   | Criterion                                                              | Weight | Note                                                                   |
| --- | ---------------------------------------------------------------------- | ------ | ---------------------------------------------------------------------- |
| 1   | Free and genuinely open source                                         | High   | Including: the features listed below are not behind an enterprise wall |
| 2   | Resource footprint                                                     | High   | Every GB competes with two JVMs on one node                            |
| 3   | Logs — view, filter, analyse                                           | High   |                                                                        |
| 4   | Metrics — Kubernetes _and_ application, including **business** metrics | High   | The importer requirement above                                         |
| 5   | Dashboards                                                             | High   |                                                                        |
| 6   | Alerting                                                               | High   | ADR-012 requires it before launch                                      |
| 7   | Easy to install as IaC on k3s                                          | Med    | A Helm chart in the same GitOps flow as everything else                |
| 8   | Easy to operate and use                                                | Med    | One developer, evenings                                                |
| 9   | Actively maintained, real community, good docs                         | Med    |                                                                        |
| 10  | Number of moving parts                                                 | Med    | Each component is a thing to upgrade, patch and debug                  |

**On criterion 1, "open source" needs splitting.** All five candidates are open source by licence. They differ in whether the _edition you can run for free_
carries the features above — SigNoz and VictoriaMetrics both ship Community and Enterprise editions, and OpenObserve likewise. What matters is whether anything
in criteria 3–6 sits on the far side of that line. For the features this project needs, none of them do — but it is worth checking again before committing,
because that line moves.

## Candidate options

### Option A — OpenObserve

A single Rust binary doing logs, metrics, traces, dashboards and alerting, storing data as **Parquet on object storage**.

- **Pros**: By a distance the smallest footprint of anything that covers all four signals — one process, not a stack. **It can use Hetzner Object Storage**,
  which this project is already creating for OpenTofu state, so retention stops competing with the node's 80 GB disk and becomes a bucket that costs cents.
  Ingests OpenTelemetry natively, so the application-side instrumentation is backend-agnostic. One Helm chart. Claims ~140× lower storage cost than
  Elasticsearch and roughly half the CPU under ingest.
- **Cons**: **AGPL-3.0** since November 2023 (it was Apache 2.0 before). For running an unmodified self-hosted copy that is unproblematic — we are not
  distributing it and not offering it as a service — but it is a licence that deserves a deliberate sentence rather than a shrug, and it would matter if the
  thing were ever forked or embedded. The youngest and smallest community of the five, so the "someone has hit this before" factor is weakest. Its dashboards
  are competent but not Grafana.

### Option B — VictoriaMetrics + VictoriaLogs + Grafana

The efficiency-first assembly: `vmsingle` for metrics, `vmagent` to scrape, `vmalert` + Alertmanager for rules, VictoriaLogs for logs, Grafana on top.

- **Pros**: **Apache 2.0 throughout**, with no licence question to think about at all. VictoriaMetrics is the most memory-efficient Prometheus-compatible TSDB
  available and is a drop-in for `kube-prometheus-stack` at a fraction of the RAM. Grafana is the strongest dashboard tool here and — importantly — **also
  answers the "do we need Superset?" question**, because it can query PostgreSQL directly for business dashboards. PromQL, so every Kubernetes dashboard and
  alert rule ever written works unmodified.
- **Cons**: **Five components rather than one.** Each is small, but each is a Helm release, an upgrade cadence and a failure mode. Tracing is a separate,
  younger product (VictoriaTraces). This is the option that costs the least RAM and the most attention.

### Option C — SigNoz

OpenTelemetry-native, single-pane traces/metrics/logs on a **ClickHouse** backend.

- **Pros**: Apache 2.0 core. The best trace exploration of the five by some margin, and genuinely one product rather than an assembly. Excellent if distributed
  tracing is the primary need.
- **Cons**: **ClickHouse is the problem on this hardware.** SigNoz's own production examples request 1 CPU / 4 GiB for ClickHouse alone with limits at 2 CPU / 8
  GiB — before the collector, query service, Alertmanager and frontend. That is the whole node. Tracing is also the signal this project needs _least_: two
  services and one database, where the interesting failures are "a scraper silently returned nothing", not "which of forty microservices added 200 ms".

### Option D — kube-prometheus-stack (+ Loki)

The industry default: Prometheus, Alertmanager, Grafana, node-exporter, kube-state-metrics, plus Loki and Promtail for logs.

- **Pros**: The most documented, most examples, most community answers, most transferable skill. Every Kubernetes question on the internet assumes it. Apache
  2.0.
- **Cons**: The heaviest total once logs are included — Prometheus alone commonly sits at 1–2 GB for a modest cluster, and Loki adds its own stack. It is the
  correct answer on a machine with room; it is the wrong answer as the _first_ thing installed on a node that also has to run two JVMs. Note that Option B is
  PromQL-compatible, so choosing it keeps essentially all of this option's ecosystem benefit.

### Option E — Netdata

Per-second infrastructure monitoring with automatic discovery of everything.

- **Pros**: GPL-3.0. The lowest-effort install of the five and genuinely excellent, zero-configuration infrastructure and container metrics — it will tell you
  more about the node in five minutes than any other option here.
- **Cons**: **It is an infrastructure monitor, not an observability platform.** Log analytics is not its strength, long-term storage and querying of _custom
  application_ metrics is secondary, and the business-metric requirement above — the one that catches a silently-broken scraper — is not what it is built for.
  Its best UI experience is Netdata Cloud, which is a hosted third party and therefore a processor, which cuts against
  [ADR-012's amendment](ADR-012_CLOUD_PLATFORM.md). Strong as a _complement_, weak as the answer.

## Comparison

| Criterion (weight)                  | A OpenObserve  | B VM + VictoriaLogs | C SigNoz       | D kube-prom + Loki   | E Netdata        |
| ----------------------------------- | -------------- | ------------------- | -------------- | -------------------- | ---------------- |
| Free & OSS (High)                   | 🟡 AGPL-3.0    | ✅ Apache 2.0       | ✅ Apache 2.0  | ✅ Apache 2.0        | ✅ GPL-3.0       |
| Footprint (High)                    | ✅ ~0.6–1 GB   | ✅ ~0.8–1.2 GB      | ❌ ~4–6 GB     | ❌ ~3–4 GB           | ✅ ~0.2 GB       |
| Logs (High)                         | ✅ First-class | ✅ VictoriaLogs     | ✅ First-class | 🟡 Loki, extra stack | ❌ Weak          |
| K8s + app metrics (High)            | ✅ OTel        | ✅ PromQL           | ✅ OTel        | ✅ PromQL            | 🟡 Infra-focused |
| **Business metrics + alert** (High) | ✅             | ✅                  | ✅             | ✅                   | ❌ Not the model |
| Dashboards (High)                   | 🟡 Good        | ✅ Grafana          | 🟡 Good        | ✅ Grafana           | 🟡 Own UI        |
| Alerting (High)                     | ✅ Built in    | ✅ vmalert          | ✅ Built in    | ✅ Alertmanager      | 🟡 Basic         |
| IaC install on k3s (Med)            | ✅ One chart   | 🟡 Several charts   | ✅ One chart   | 🟡 Two stacks        | ✅ One chart     |
| Ease of use (Med)                   | ✅             | 🟡 Assembly         | ✅             | 🟡                   | ✅               |
| Community & docs (Med)              | 🟡 Youngest    | ✅ Strong           | ✅ Strong      | ✅ Strongest         | ✅ Strong        |
| Moving parts (Med)                  | ✅ One         | ❌ Five             | 🟡 Four + CH   | ❌ Seven+            | ✅ One           |

## Decision

**Option A — OpenObserve, single-node, with Hetzner Object Storage as its backing store.**

Rationale:

- **It is the only candidate that covers all four required capabilities in one process.** For a project whose scarcest resource is evenings, the difference
  between one Helm release and five is not cosmetic — it is the difference between an observability stack that gets upgraded and one that quietly rots.
- **Object storage changes the retention conversation.** Every other option stores data on the node's disk, which means retention is capped by the same 80 GB
  that holds container images and the k3s state, and a log spike is a disk-full outage. OpenObserve writes Parquet to a bucket that already has to exist for
  OpenTofu state. Retention becomes a policy rather than a risk.
- **It leaves room for the application.** ~1 GB against SigNoz's ~5 GB decides the node size, and on the sizing in PLATFORM_SETUP.md §3 that is the difference
  between a €8.49 CX33 being plausible and a €15.99 CX43 being mandatory.
- **The instrumentation is not locked in.** Both applications will emit OpenTelemetry and Prometheus-format metrics regardless. If OpenObserve disappoints,
  swapping the backend is a Helm change and a datasource change — not a re-instrumentation. That is deliberate, and it is what makes proposing the youngest
  product acceptable.

**The AGPL is accepted knowingly.** We self-host an unmodified upstream build for internal use; §13 of the AGPL is not triggered by that, and there is no
distribution. It would matter if OpenObserve were ever modified and exposed as a service to third parties, which is not a thing this project plans to do.

**If the recommendation is rejected**, the ranked fallbacks are:

1. **Option B — VictoriaMetrics + VictoriaLogs + Grafana.** Take this if the AGPL is unwelcome, if the small community is judged too thin a bus factor, or if
   Grafana's dashboards are wanted for their own sake. It costs about the same RAM and materially more attention. It is the **safest** answer here, just not the
   simplest, and choosing it also settles the Superset question the same way.
2. **Option D — kube-prometheus-stack + Loki.** Take this if the node is upsized well past 16 GB, or if maximum transferability of the skill matters more than
   the resources. Note Option B already gives most of the ecosystem benefit at a third of the cost.
3. **Option C — SigNoz.** Take this only if distributed tracing becomes the primary need, and only on hardware with ClickHouse-sized headroom.

**Netdata is recommended as a complement, not an alternative.** Roughly 200 MB for per-second node and container visibility with no configuration is good value
alongside whichever option wins, and it is the fastest way to answer "what is eating the node". Run it self-hosted only — **not** connected to Netdata Cloud,
which would add a processor that ADR-012's amendment just finished removing.

### When to revisit

- **Any of the five trial tests in §Status fails.** That is the primary trigger and the reason this was accepted rather than left Proposed — the exit is
  pre-decided (fallback 1), so failing a test is a Helm change, not a fresh evaluation.
- **The node is upsized past 16 GB for unrelated reasons** — the footprint argument, which is most of this ADR, weakens considerably.
- **Distributed tracing becomes the question being asked** — that is Option C's home ground, and it would be a fair reason to spend the RAM.
- **OpenObserve's release cadence or issue-response visibly slows** — the youngest-project risk materialising. Option B is the pre-decided exit and needs no
  re-instrumentation.
- **A second cluster or a team appears** — the calculus behind "fewest moving parts" changes when more than one person is on call.

## Consequences

- **Positive**: One Helm release covers logs, metrics, dashboards and alerts. Retention lives in object storage, not on the node's disk. The footprint keeps the
  single-node design viable. Application instrumentation is vendor-neutral OpenTelemetry, so the backend stays replaceable.
- **Negative**: AGPL-3.0 rather than a permissive licence. The smallest community of the candidates, so obscure problems will be lonelier. Dashboards are good
  but not Grafana — if a genuinely rich dashboard need appears, Grafana can be added later pointing at the same data, at ~200 MB.
- **A second bucket is needed** in Hetzner Object Storage, separate from the OpenTofu state bucket, with its own credentials and its own lifecycle policy.
  **This is not a new processor** — same Hetzner GmbH, already named in both privacy notices and covered by the single AVV that
  [#275](https://github.com/enorm-labs/event-junkie/issues/275) tracks.
- **Log content is now a privacy decision with a place to be enforced.** [LEGAL.md](../LEGAL.md) §7.5's four open logging decisions — whether to log client IPs,
  truncation, retention, and _where retention is enforced_ — get their answer to the last one here: OpenObserve's retention policy on the bucket. That closes a
  gap ADR-012's amendment widened, since without an edge proxy the origin now sees real client IPs.
- **Superset is not needed** (PLATFORM_SETUP.md §4). If business dashboards outgrow OpenObserve's, the next step is Grafana with a PostgreSQL datasource, not a
  separate BI platform with its own Python stack and metadata database.
- **Alert routing is Signal**, via a webhook destination into `signal-cli-rest-api` in the cluster — picked because it is end-to-end encrypted, so alert bodies
  carrying venue data, error strings and possibly IPs are unreadable by the carrier. See [PLATFORM_SETUP.md](../ops/PLATFORM_SETUP.md) §5a, which also records the
  thing this ADR cannot solve on its own: **alerting that runs on the monitored node cannot report that node's death**, so an external uptime monitor and a
  dead-man's-switch heartbeat are required alongside it. An alert nobody sees at 23:00 is not alerting.
- **The importer must emit per-source business metrics** for any of this to catch the failure that actually matters. That work is in PLATFORM_SETUP.md §7 and is
  a prerequisite for go-live, not a follow-up.

## References

- [OpenObserve](https://github.com/openobserve/openobserve) — AGPL-3.0, single binary, object-storage backed
- [SigNoz](https://github.com/signoz/signoz) · [SigNoz resource planning](https://signoz.io/docs/setup/capacity-planning/community/resources-planning/) — the
  ClickHouse sizing that rules it out here
- [VictoriaMetrics](https://github.com/VictoriaMetrics/VictoriaMetrics) · [VictoriaLogs](https://docs.victoriametrics.com/victorialogs/) — Apache 2.0, Community
  and Enterprise editions
- [Netdata](https://github.com/netdata/netdata) — GPL-3.0
- [ADR-012 — Cloud platform](ADR-012_CLOUD_PLATFORM.md), especially _"observability is now our problem"_ and the 2026-08-10 amendment
- [PLATFORM_SETUP.md](../ops/PLATFORM_SETUP.md) — sizing, the instrumentation work, and the go-live sequence
- [LEGAL.md](../LEGAL.md) §7.5 — the four logging decisions this stack has to enforce
