# ADR-012: Cloud Platform & Hosting

## Status

**Accepted (2026-08-10) — Option A: Hetzner Cloud (Nuremberg/Falkenstein), k3s + Helm, OpenTofu, PostgreSQL on a dedicated VM with `wal-g` PITR backups.**

Proposed 2026-08-03 · revised 2026-08-05 · accepted 2026-08-10. The recommendation was accepted as written; no fallback was taken, so the frontend-hosting and
CORS posture below stands unchanged (containerised SPA, same origin as the API). The only substantive edit made at acceptance was to add **Option G — Hetzner
compute + managed EU Postgres** to the ranked fallbacks, where it had been named as a revisit trigger but omitted from the list.

> Resolves [issue #258](https://github.com/enorm-labs/event-checker/issues/258) — *"Settle the cloud platform"*, the first item in the `v0.2 — Deployable` milestone. This ADR
> picks
> the **platform**; the Terraform/OpenTofu layout, the Helm chart, and the CI/CD workflows are follow-up items that depend on it. All prices in this document
> were checked on **2026-08-03**, and the PaaS / Elastic Beanstalk / App Engine sections were added on **2026-08-05**. They must be re-verified before the money
> is actually committed — cloud list prices moved twice in 2026 already (see
> [Hetzner's 15 June 2026 adjustment](https://docs.hetzner.com/general/infrastructure-and-availability/price-adjustment/)). Accepting this ADR commits nothing;
> the re-check belongs to [#260](https://github.com/enorm-labs/event-checker/issues/260), where servers are actually provisioned.
>
> **2026-08-05 revision.** Managed-container platforms priced out at 4–6× the cheapest option, so the PaaS layer was evaluated properly rather than in one
> paragraph: European PaaS providers are now first-class candidates (Option E1), AWS Elastic Beanstalk is costed separately from Fargate (Option H), and App
> Engine is costed alongside Cloud Run (Option C). The recommendation is unchanged; the *fallback* ranking changed materially — it is now European PaaS first,
> not US PaaS.

## Context

Event Junkie is a Berlin music-events guide, **not yet deployed anywhere** (see [README §Status](../../README.md#status)). The decision is being made before the
first deploy, which means we are choosing the platform we will write Terraform and a Helm chart *against* — switching later costs real work, so it is worth
recording why.

### What actually has to run

Four deployables today, derived from the modules in this repo, plus one more that the backlog makes near-certain:

| Component         | Shape                                                             | Runtime demand                                                     |
|-------------------|-------------------------------------------------------------------|--------------------------------------------------------------------|
| `events-bff`      | Spring Boot 4 / WebFlux / R2DBC, read-only public API (port 8080) | Stateless, horizontally scalable, JVM ≈ 512 MB–1 GB heap           |
| `events-importer` | Spring Boot 4 / WebFlux / R2DBC + admin API (port 8081)           | **Always-on, effectively single-instance** — see below             |
| `events-frontend` | Vue 3 + Vite **SPA** — `npm run build` emits a static `dist/`     | No server runtime of its own; static files + a router fallback     |
| PostgreSQL 18     | The only stateful component; owns all event/venue/artist data     | ~2 vCPU / 4 GB, tens of GB, needs backups + point-in-time recovery |
| *admin frontend*  | **Planned** (TODO 🟠) — a second static SPA over the admin API    | Static files, one user, **must not be publicly reachable**         |

**On the planned admin frontend.** TODO lists an admin UI to operate the importers and curate data (imports status, import configuration, data-quality overview,
manual event entry). It is not built yet, but it is close enough to change two things here, so it is priced and designed for rather than discovered later:

- **It is a fifth deployable, and its cost is not uniform across the options.** On Hetzner or any container platform it is another nginx serving a few MB —
  noise. On a **per-GB-RAM PaaS it is another billed container** (~€25–30/month on Scalingo, $25 on Render), which is a second reason those options want their
  SPAs on a static host rather than in a container. On Cloud Run it is close to free, because admin traffic is one person and an admin service can genuinely
  scale to zero.
- **It forces the admin-API exposure question that would otherwise be deferred.** The importer's admin API must not be public (below), and the current answer is
  `kubectl port-forward`. A browser-based admin UI either runs locally against that port-forward — free, fine for one developer, and the launch answer — or it
  gets deployed, and then it needs an access-control mechanism *at the edge* before the planned authentication work lands. Platforms differ here: Cloud Run has
  IAM/IAP for exactly this, Hetzner + Traefik needs Cloudflare Access (free for a handful of users), an IP allowlist, or a basic-auth middleware, and most PaaS
  options offer nothing below the application layer. This is a small point in favour of the recommendation only insofar as Cloudflare is already in the design.

Three properties of `events-importer` constrain the platform choice more than anything else:

1. **It is a scheduler, not a request handler.** [ADR-008](ADR-008_IMPORT_JOB_SCHEDULING.md) runs a `@Scheduled(fixedDelay = 60s)` tick that queries
   `event_source` for due venues. A wall-clock tick every minute is incompatible with request-driven scale-to-zero (Cloud Run request-billing, Lambda, Container
   Apps scale-to-zero) — the tick simply does not fire when there are no requests.
2. **It must be exactly one instance.** ADR-008 is explicit that the `status = 'RUNNING'` exclusion "is not a true lock" and that multi-instance operation needs
   `SELECT … FOR UPDATE SKIP LOCKED` first. Any platform whose default deploy strategy runs old and new replicas concurrently needs `maxSurge: 0` / `Recreate`
   semantics, or we accept the idempotency argument in ADR-008.
3. **Its jobs are long.** A heavy importer (Badehaus) fetches ~90 throttled detail pages and runs **over a minute**; the staleness guard is 30 minutes. Request
   timeouts on serverless container platforms (Cloud Run caps at 60 min, App Runner at 120 s per request) are a real constraint — mitigated here because ADR-008
   already made manual triggers fire-and-forget, but it rules out anything with a short hard ceiling.

Also relevant: [ADR-005](ADR-005_MIGRATIONS_OWNED_BY_IMPORTER.md) puts Flyway in the importer, so the importer must reach the database before the BFF serves
traffic, and the importer's admin API **must not be publicly reachable**.

### Non-functional context

- **Scale is small and will stay small for a while.** Eight venues live today, ~40 planned; the public API is read-mostly and cacheable. Realistic launch
  traffic is well under 100 GB egress/month.
- **One developer.** Ops time is the scarcest resource, scarcer than euros — but euros are not free either, and a €200/month bill for a pre-revenue side project
  is its own kind of pressure.
- **A separate staging stage is required** (TODO 🔴 Now). On every platform with per-hour floors this roughly doubles the bill, so "cost of the second
  environment" is a first-class criterion, not an afterthought.
- **EU / Germany hosting is a hard requirement.** The product is German (`event-junkie.de`), the users are in Berlin, and the data includes third-party website
  content plus — once accounts land (TODO 🟠 Next) — personal data under GDPR.
- **Existing skills**: Docker, Kubernetes, Helm, Terraform. TODO already plans a Helm chart and to exercise it locally on k3d/kind, so a Kubernetes-shaped
  target reuses work that is going to happen anyway.

### Criteria

| #  | Criterion                           | Weight | Why it matters here                                                                                         |
|----|-------------------------------------|--------|-------------------------------------------------------------------------------------------------------------|
| 1  | Fit for always-on JVM + scheduler   | High   | ADR-008's tick rules out scale-to-zero for the importer; JVM cold starts (2–5 s) hurt request-billed models |
| 2  | Managed PostgreSQL (backup/PITR)    | High   | The only stateful thing we own; losing it loses everything. Self-managing it is the main hidden cost        |
| 3  | Total cost at *this* scale          | High   | Pre-revenue. Per-hour floors (LB, NAT, DB) dominate — not per-request pricing                               |
| 4  | Cost of a second (staging) stage    | High   | Explicitly required; on hyperscalers it is nearly a second full bill                                        |
| 5  | EU / German data residency          | High   | GDPR, German users, German domain; DPA/AVV and jurisdiction matter                                          |
| 6  | Ops burden                          | High   | Who patches the OS, the DB, the K8s control plane — paid in evenings, not invoices                          |
| 7  | Reuse of Docker/K8s/Helm/Terraform  | Med    | Skills already held; a Helm chart is already on the backlog                                                 |
| 8  | Terraform/OpenTofu provider quality | Med    | IaC is the next backlog item after this one                                                                 |
| 9  | CI/CD integration (GitHub Actions)  | Med    | Build workflows already exist; want OIDC over long-lived keys                                               |
| 10 | Egress predictability               | Med    | Metered egress is the classic source of bill shock                                                          |
| 11 | Observability included              | Med    | TODO wants monitoring/alerting/dashboards; "included" beats "assemble"                                      |
| 12 | Lock-in / exit cost                 | Med    | A managed-container + managed-Postgres app is portable; proprietary glue is not                             |
| 13 | Scaling headroom                    | Low    | Nothing here needs to scale for a long time, but the door shouldn't be nailed shut                          |
| 14 | Career / CV signal                  | Low    | Real, but it is a tie-breaker, not a driver                                                                 |

On criterion #5, two things are being asked for and they are not the same: **data residency** (the bytes sit in the EU — satisfied by an EU region at any US
provider, under SCCs) and **jurisdiction** (the contracting entity is European, so no CLOUD Act analysis is needed — satisfied only by Hetzner, Scalingo, Clever
Cloud, Upsun, Koyeb, Sliplane, or AWS's European Sovereign Cloud). Residency is the hard requirement; jurisdiction is the strong preference, and the tables
below score them separately.

### Which platforms are actually popular?

Worth stating plainly, because "popular" and "right for this" are different questions. Per Synergy Research for 2026, the global cloud-infrastructure market is
roughly **AWS ~28–31 %, Microsoft Azure ~21–25 %, Google Cloud ~11–14 %** — together about two-thirds of the market. Everything else (Alibaba, Oracle, IBM,
DigitalOcean, Hetzner, OVH, Scaleway, and the PaaS layer of Fly/Render/Railway/Vercel) shares the remaining third.

So yes — **AWS is the market leader and the enterprise default.** That is a fact about procurement, hiring, and breadth of catalogue. It is not, by itself, an
argument that AWS is the right home for two containers and a small Postgres, and this ADR distinguishes the two.

---

## Candidate options

### Option A — Hetzner Cloud (Nuremberg / Falkenstein) + k3s

German company (Gunzenhausen), German data centres, ISO 27001, standard AVV. Compute is plain VMs; we run **k3s** on them with the official
`hcloud-cloud-controller-manager` and CSI driver, deploy with the Helm chart TODO already plans, provision with the `hetznercloud/hcloud` Terraform provider.
PostgreSQL runs on its **own VM** (not inside the cluster) with `wal-g`/`pgBackRest` streaming WAL to Hetzner Object Storage or a Storage Box.

- **Pros**: By far the cheapest — a factor of 5–6 versus AWS for this workload. 20 TB egress **included** per server, so no bandwidth bill shock. Cleanest GDPR
  story of any candidate (German entity, German jurisdiction, no CLOUD Act exposure). Every existing skill applies directly. A second (staging) environment
  costs
  ~€7/month, so the required staging stage is genuinely affordable. Always-on is the default, so ADR-008's scheduler needs no workarounds.
- **Cons**: **No managed PostgreSQL** — backups, PITR, restore drills, minor-version upgrades and disk-full are ours. We also own the k3s control plane, OS
  patching, and node upgrades. No managed observability (Prometheus/Grafana or an external SaaS is on us). Single-region; DDoS protection is basic (Hetzner has
  volumetric protection, but no WAF/rate-limiting layer). Support is email-only.
- **Hetzner raised cloud prices on 15 June 2026** (CX23 €3.99 → €5.49; the dedicated CCX/CPX lines up to 2–3×). Still the cheapest by a wide margin, but the
  "Hetzner never raises prices" assumption is now dead and should not be planned around.

### Option B — AWS (ECS Fargate + RDS + ALB + CloudFront/S3), `eu-central-1`

The market-leading, maximal-optionality choice. *(AWS's own PaaS — Elastic Beanstalk — is a materially different cost and ops profile and is evaluated
separately as **Option H**.)*

- **Pros**: Largest service catalogue by far; if we later want managed Elasticsearch/OpenSearch (README lists it as "maybe later"), SQS, EventBridge, or Cognito
  for the planned auth, it is all one `terraform apply` away. Best-in-class Terraform provider and by far the most documentation, examples, and hiring signal.
  RDS is a genuinely excellent managed Postgres (automated backups, PITR, one-click restore, Multi-AZ when we want it). GitHub Actions OIDC is first-class.
  Frankfurt (`eu-central-1`) satisfies EU residency, and since **15 January 2026** the **AWS European Sovereign Cloud** is generally available with its first
  region in Brandenburg, Germany — separate German legal entity, EU-resident staff, independent IAM/billing/DNS — which is the strongest answer available to the
  CLOUD Act concern, at a price premium and with a smaller service set.
- **Cons**: **The fixed floor is the problem, not the variable cost.** An ALB bills ~$24/month whether or not anyone visits; a NAT Gateway adds ~$38/month
  before a byte moves; RDS bills per hour; Fargate has no scale-to-zero. None of that is elastic at our size — we would pay for capacity nobody uses. ECS +
  Fargate is also markedly more assembly-required than Cloud Run or Container Apps (task definitions, target groups, listener rules, execution roles, log
  groups), so it is the most Terraform to write and own. Egress is metered at ~$0.09/GB, plus a further ~$0.052/GB if it traverses NAT. Staging nearly doubles
  the bill.

### Option C — Google Cloud (Cloud Run or App Engine + Cloud SQL), `europe-west3` (Frankfurt)

The best hyperscaler *ergonomics* for exactly this workload shape.

- **Pros**: Cloud Run is the nicest managed-container experience of the three — you push an image, you get an HTTPS endpoint with a managed certificate, and
  **there is no load-balancer line item**, which removes ~$24/month of AWS's floor outright. Scale-to-zero makes the **staging** environment nearly free, which
  directly serves criterion #4. Cloud SQL is a solid managed Postgres. Cloud Build/Artifact Registry and GitHub OIDC integrate cleanly.
- **Cloud Run *worker pools* (GA in 2026) close the ADR-008 gap.** A worker pool is a non-HTTP, always-on, **manually scaled** Cloud Run deployment built for
  pull/background work — instances run continuously, there is no request-based billing and no per-request fee, and Google prices always-allocated CPU ~25 % and
  memory ~20 % below the request-billed rates. `events-importer` fits this primitive exactly: `@Scheduled` ticks fire because the instance never stops, and
  "manually scaled to 1" is the platform-native expression of ADR-008's single-instance constraint. This is a real improvement over `min-instances: 1` on a
  service, which was the awkward part of this option when the ADR was first drafted. The BFF stays an ordinary Cloud Run service.
- **Cons**: **Frankfurt is a Tier 2 Cloud Run region** — CPU $0.0000336/vCPU-s and memory $0.0000035/GiB-s versus $0.000024 / $0.0000025 in Tier 1 — so
  `europe-west3` costs roughly a third more than `europe-west1` (Belgium) or `europe-west4` (Netherlands) for identical containers. Both Tier 1 alternatives are
  still EU territory, so the German *preference* costs
  about $30/month here; the EU *requirement* costs nothing. Cloud SQL has awkward small-instance economics (a public IPv4 alone is ~$9.57/month idle; private IP
  needs Direct VPC egress or a connector). Google's product-deprecation reputation is a real, if often overstated, planning risk. Same CLOUD Act posture as AWS,
  and no German-jurisdiction sovereign offering equivalent to AWS's ESC.

#### C2 — App Engine, evaluated because it was asked for, and rejected

App Engine is Google's original PaaS and can run a Spring Boot fat JAR, so it belongs in a PaaS evaluation. It loses to Cloud Run on every axis that matters
here:

- **Standard environment** bills per *resident instance-hour* by instance class: F1/B1 $0.05, F2/B2 $0.10, F4/B4 $0.20, F4_1G $0.30 per hour (US rates;
  `europe-west3` carries a regional premium on top). Our JVMs need ≥512 MB, and the scheduler needs a resident instance — which means `basic`/`manual` scaling,
  i.e. **no scale-to-zero and no free-quota relief** (the free tier is 28 instance-hours/day, enough for one tiny instance). F2 ≈
  **$73/month** per always-on service and F4 ≈ **$146/month**. That is 2–5× Cloud Run for the same container.
- **Flexible environment** bills $0.0526/vCPU-hour + $0.0071/GB-memory-hour (again plus a European premium) and **cannot scale below one instance**. One 1
  vCPU / 1 GB service is ≈ **$44–48/month**, so bff + importer ≈ **$95/month** before the database — and deploys take minutes because each one rolls a VM.
- **Structural annoyances**: one App Engine application per GCP project, and its **region is fixed for the life of the project** — moving to another region
  means a new project. Google now steers new workloads to Cloud Run and ships `gcloud app migrate-to-run` tooling in the other direction; the first-generation
  runtimes (including Java 8) were deprecated on 31 January 2026.

**Verdict**: within Google Cloud, Cloud Run + worker pools strictly dominates App Engine for this application. App Engine is not carried into the comparison
tables except in the cost summary, where it is shown to document the gap.

### Option D — Azure (Container Apps + PostgreSQL Flexible Server), Germany West Central

- **Pros**: Container Apps has Cloud Run-like ergonomics with free built-in ingress, and it is KEDA-based so cron-style scaling is native. Azure Database for
  PostgreSQL **Flexible Server** has the friendliest small-instance pricing of the three hyperscalers (B-series burstable, ~$13–15/month for B1ms). Germany West
  Central is a full region.
- **Cons**: The smallest third-party ecosystem of the three for this stack; Terraform's `azurerm` provider is good but the resource model is chattier. No
  compelling advantage over Option C for us, and the same jurisdictional posture. Included mainly for completeness.

### Option E — Platform-as-a-Service

The PaaS layer is where "one developer, no evenings for ops" and "EU data residency" can both be satisfied without hyperscaler floor costs, so it is worth
splitting into three genuinely different families rather than treating "PaaS" as one option.

What all of them share: push a Dockerfile (or a Git branch), get a URL, TLS, logs, and — in most cases — a managed Postgres with automated backups. All of them
support **always-on containers**, so ADR-008's scheduler is unproblematic on every candidate below. What they also share: **none of the Docker/K8s/Helm skills
carry over past the Dockerfile** — hiding that layer is the entire product — and their Terraform providers are thin or absent, which weakens criterion #8.

#### E1 — European PaaS (preferred family if a PaaS is chosen)

| Provider         | Jurisdiction       | Regions                              | Managed Postgres              | Notes                                                            |
|------------------|--------------------|--------------------------------------|-------------------------------|------------------------------------------------------------------|
| **Scalingo**     | 🇫🇷 SAS, Strasbourg | `osc-fr1`, `osc-secnum-fr1` (France) | ✅ Starter/Business, **PITR** | "Heroku, but European". ISO 27001, HDS, SecNumCloud region       |
| **Clever Cloud** | 🇫🇷 SAS, Nantes     | Paris, Gravelines + EU partners      | ✅ PostgreSQL add-on          | Native Spring Boot build pack, per-second billing, ISO 27001     |
| **Upsun**        | 🇫🇷 Platform.sh SAS | Multiple EU regions                  | ✅ as a project "service"     | Git-branch-per-environment; best staging story, worst price/perf |
| **Koyeb**        | 🇫🇷 SAS             | Frankfurt (DE), Paris, others        | ✅ serverless (Neon-style)    | Scale-to-zero; Frankfurt satisfies the German preference         |
| **Sliplane**     | 🇩🇪 GmbH            | Germany (on Hetzner), Finland        | ❌ a DB is just a container   | Flat per-server price, unlimited containers, free egress         |
| **Northflank**   | 🇬🇧 UK (adequacy)   | Frankfurt, NL, Zurich, EU West       | ✅ Postgres addon             | Most Kubernetes-like of the set; BYOC possible                   |

- **Pros**: Ops burden collapses to "watch the dashboard". **Scalingo and Clever Cloud are the only candidates in this entire ADR besides Hetzner that combine
  EU jurisdiction with a managed PostgreSQL that has automated backups and PITR** — i.e. they remove the single biggest drawback of Option A without importing a
  CLOUD Act question. Scalingo's SecNumCloud region is a stronger sovereignty claim than anything AWS or Google offers outside AWS's ESC. Prices sit clearly
  below the hyperscalers.
- **Cons**: Roughly **3–5× Hetzner** (see pricing below) — per-GB-RAM container pricing is how PaaS makes money, and two always-on JVMs at 1 GB each is exactly
  the shape it charges most for. Small ecosystems: fewer StackOverflow answers, thinner Terraform providers, and a bus factor question a hyperscaler does not
  have. Sliplane is the odd one out — German, cheapest, but it gives you PaaS *ergonomics* without a managed database, so it does not actually retire the
  Postgres-ops risk that motivates looking at PaaS at all.

#### E2 — US PaaS with EU regions: Heroku / Fly.io / Render / Railway / DigitalOcean App Platform

- **Pros**: The most mature developer experience of the lot, Heroku especially — it is the platform every other one on this page imitates. Render has Frankfurt,
  Fly has `fra`, Railway has EU West (Amsterdam), DigitalOcean App Platform has `fra1`, Heroku's Common Runtime has an EU region (Ireland, on AWS).
- **Cons**: All are **US companies**, so EU *residency* is satisfiable but EU *jurisdiction* is not — acceptable under SCCs, weaker than a German or French
  provider against the stated preference, and one more processor in the GDPR record. **Heroku is also the most expensive PaaS here** once you need production
  dynos (Standard-2X at 1 GB is $50/month *each*) and a Standard-tier Postgres ($50/month) — it prices out near AWS Fargate while offering less. Fly, Render and
  Railway are cheaper but their managed Postgres offerings are the least battle-tested part of each product, and the database is the thing we least want to be
  adventurous about.

#### E3 — Self-hosted PaaS on Hetzner (Coolify / Dokku / CapRover), or Sliplane

Worth naming explicitly because it is the option that answers "PaaS ergonomics are what I actually want, the bill is what I actually object to": run
[Coolify](https://coolify.io/) (or Dokku/CapRover) on a Hetzner VM and get push-to-deploy, TLS, and a web dashboard at Hetzner prices — or pay Sliplane €9–24 a
month to run that layer for you on the same German hardware.

- **Pros**: Cost identical to Option A (~€25–35/month for prod + staging). Push-to-deploy without writing a Helm chart. German data centres either way.
- **Cons**: **It does not give you managed PostgreSQL.** Coolify will happily run a Postgres container with a backup cron; that is still our backups, our
  restores, our upgrades. So E3 buys deployment ergonomics, not the thing Option A's "Negative" section is actually worried about. Coolify itself is one more
  self-hosted control plane to patch.

### Option F — Managed Kubernetes at a hyperscaler (EKS / GKE / AKS)

Evaluated and **rejected on price alone**: EKS and GKE both charge roughly **$0.10 per cluster-hour ≈ $73/month for the control plane before a single pod runs**
(GKE waives one zonal cluster; AKS's free tier has no uptime SLA). Add nodes, a load balancer, NAT, and a managed database and the floor is $150–250/month per
environment. That is the correct answer for a team running many services; it is indefensible for two containers. Note that this rejection is about *managed K8s
at hyperscaler prices*, not about Kubernetes — Option A uses Kubernetes, just with a control plane we run ourselves for ~€0.

### Option G — Hybrid: cheap EU compute + specialist managed Postgres

Hetzner (or a PaaS) for compute, paired with **Neon** (~$0.106/CU-hour on Launch, EU regions available), **Aiven** (Finnish/EU company, ~$60–80/month for a
production-grade small plan), or **Supabase Pro** (~$25/month) for the database.

- **Pros**: Removes the single biggest drawback of Option A — we stop owning Postgres backups and PITR — while keeping cheap compute. Neon's branching is a
  genuinely nice fit for a staging stage.
- **Cons**: Two vendors, two DPAs, two bills, and cross-provider network latency on every query, which matters for a WebFlux/R2DBC app doing chatty per-event
  upserts. Aiven is EU-owned; Neon and Supabase are US companies with EU regions.

### Option H — AWS Elastic Beanstalk (`eu-central-1`)

AWS's own PaaS, and the cheapest way to run this application *on AWS*. Beanstalk provisions plain EC2 instances (Amazon Linux 2023, Corretto or Docker
platform), an optional load balancer, auto-scaling group, and CloudWatch wiring from a single `eb deploy` — and **Beanstalk itself is free**; you pay only for
the resources underneath. It is actively maintained: platform updates shipped roughly monthly through 2026, and the AL2-based branches retire on 30 June 2026 in
favour of AL2023.

- **Pros**: Removes the two line items that make Option B expensive. A **single-instance environment** has *no load balancer*
  (~−$24/month) and sits in a public subnet, so there is **no NAT Gateway** (~−$40/month) — that is $64/month of Option B's floor deleted, which is why
  Beanstalk lands at roughly half of Fargate. EC2 is cheaper per GB of RAM than Fargate, and Graviton (`t4g`) is cheaper again. RDS is still available as the
  managed Postgres, which is the best in this comparison. **Beanstalk's single-instance deploy model — terminate, then start — is exactly the `Recreate`
  semantics ADR-008 needs for the importer**, for free. Everything else about AWS (OIDC from GitHub Actions, `eu-central-1` residency, the ESC option,
  CloudWatch, the Terraform provider) still applies.
- **Cons**: It is a **PaaS veneer over EC2, not a container platform** — you own instance sizing and capacity, and while Beanstalk offers managed platform
  updates, patching is a thing you configure rather than a thing that vanishes. Still ~3× Hetzner and ~2× a self-hosted PaaS. The deploy model is dated
  (application versions in S3, `.ebextensions`, an environment per service — so two services means two environments and two EC2 instances unless we co-locate
  them). **Do not let Beanstalk create the RDS instance**: an environment-owned database is destroyed with the environment, so the database must be provisioned
  separately and passed in as configuration. AWS's own momentum is behind ECS/App Runner, so Beanstalk is stable rather than growing.

## Comparison

Scores are for **this** workload at **this** scale, not in general.

Columns: **A** Hetzner + k3s · **B** AWS Fargate · **H** AWS Beanstalk · **C** GCP Cloud Run · **E1** EU PaaS (Scalingo/Clever Cloud) · **E2** US PaaS
(Fly/Render/Heroku).

| Criterion (weight)               | A                    | B                  | H                  | C                 | E1                | E2             |
|----------------------------------|----------------------|--------------------|--------------------|-------------------|-------------------|----------------|
| Always-on JVM + scheduler (High) | ✅ Native            | ✅ Native          | ✅ + `Recreate`    | ✅ Worker pool    | ✅ Native         | ✅ Native      |
| Managed PostgreSQL (High)        | ❌ Self-managed      | ✅ RDS             | ✅ RDS             | ✅ Cloud SQL      | ✅ + PITR         | 🟡 Less proven |
| Cost at this scale (High)        | ✅ ~€22/mo           | ❌ ~$150/mo        | 🟡 ~$80–100/mo     | 🟡 ~$110–135/mo   | 🟡 ~€70–105/mo    | 🟡 ~$50–150/mo |
| Cost of staging stage (High)     | ✅ ~€7/mo            | ❌ ~+$70/mo        | 🟡 ~+$50/mo        | ✅ ~+$30, to zero | 🟡 ~+€40/mo       | 🟡 ~+$25–40/mo |
| EU residency (High)              | ✅ DE                | ✅ Frankfurt       | ✅ Frankfurt       | ✅ Frankfurt      | ✅ FR/DE          | ✅ EU region   |
| EU jurisdiction (High)           | ✅ German entity     | ❌ US (ESC option) | ❌ US (ESC option) | ❌ US entity      | ✅ EU entity      | ❌ US entity   |
| Ops burden (High)                | ❌ OS + k3s + DB     | ✅ Low             | 🟡 EC2 is ours     | ✅ Low            | ✅ Lowest         | ✅ Lowest      |
| Reuses Docker/K8s/Helm/TF (Med)  | ✅ All of it         | 🟡 Docker + TF     | 🟡 Docker + TF     | 🟡 Docker + TF    | ❌ Bypassed       | ❌ Bypassed    |
| Terraform provider quality (Med) | ✅ `hcloud`          | ✅ Best in class   | ✅ Best in class   | ✅ Very good      | ❌ Thin or absent | 🟡 Thin/uneven |
| GitHub Actions CI/CD (Med)       | 🟡 kubeconfig secret | ✅ OIDC            | ✅ OIDC            | ✅ OIDC           | 🟡 API token      | ✅ Native      |
| Egress predictability (Med)      | ✅ 20 TB included    | ❌ $0.09/GB + NAT  | 🟡 $0.09/GB        | 🟡 Metered        | 🟡 Metered/quota  | 🟡 Metered     |
| Observability included (Med)     | ❌ Bring your own    | ✅ CloudWatch      | ✅ CloudWatch      | ✅ Cloud Ops      | 🟡 Basic          | 🟡 Basic       |
| Lock-in / exit cost (Med)        | ✅ VMs + K8s         | 🟡 Moderate glue   | 🟡 Moderate glue   | 🟡 Moderate glue  | ❌ Proprietary    | ❌ Highest     |
| Scaling headroom (Low)           | 🟡 Manual, 1 region  | ✅ Unlimited       | 🟡 Vertical + ASG  | ✅ Unlimited      | 🟡 Bounded        | 🟡 Bounded     |
| Career / CV signal (Low)         | 🟡 Niche             | ✅ Strongest       | 🟡 Dated but AWS   | ✅ Strong         | ❌ Weak           | ❌ Weak        |

Option F (managed Kubernetes) is omitted from the table — it is rejected on price alone and scores as B/C everywhere else. Option G (hybrid) is a modifier on A
or E3, not a platform in its own right.

---

## Pricing comparison

### Sizing assumption

One production stage: `events-bff` 0.5 vCPU / 1 GB · `events-importer` 0.5 vCPU / 1 GB · PostgreSQL 2 vCPU / 4 GB with 40 GB storage · static SPA · < 100 GB
egress/month. Plus one staging stage at roughly half that. All figures **as of 2026-08-03** (PaaS, Beanstalk and App Engine tables: **2026-08-05**), EU regions,
list price, excl. VAT/credits.

### Option A — Hetzner Cloud (recommended)

| Item                                                 | Plan                         | € / month  |
|------------------------------------------------------|------------------------------|------------|
| k3s node — bff + importer + frontend + ingress       | CX33 (4 vCPU / 8 GB / 80 GB) | 8.49       |
| PostgreSQL VM (private network only, no public IPv4) | CX23 (2 vCPU / 4 GB / 40 GB) | 5.49       |
| Public IPv4 (k3s node only)                          | 1 ×                          | ~1.70      |
| Automated snapshots (20 % of server price)           | both servers                 | ~2.80      |
| Backup target for WAL + base backups                 | Storage Box BX11 (1 TB)      | ~3.81      |
| **Production subtotal**                              |                              | **~22.30** |
| Staging — everything on one node                     | CX23 + IPv4                  | ~7.20      |
| **Total (prod + staging)**                           |                              | **~29.50** |

Add a Hetzner Load Balancer (LB11, ~€7.49/month) only when a second k3s node arrives; until then the ingress binds the node IP directly.

### Option B — AWS `eu-central-1`

| Item                                                              | $ / month     |
|-------------------------------------------------------------------|---------------|
| Fargate — 2 tasks × (0.5 vCPU + 1 GB), x86 (ARM/Graviton ≈ −20 %) | ~40 (~32 ARM) |
| Application Load Balancer (hourly + minimum LCUs)                 | ~24           |
| RDS `db.t4g.small` Single-AZ + 40 GB gp3 + backups                | ~32           |
| NAT Gateway ($0.052/h in Frankfurt + $0.052/GB processed)         | ~40           |
| S3 + CloudFront for the SPA                                       | ~2            |
| Route 53, Secrets Manager, ECR, CloudWatch                        | ~12           |
| **Production subtotal**                                           | **~150**      |
| Without NAT (tasks in public subnets — a security trade-off)      | ~110          |
| Staging (~60 % of prod)                                           | ~70           |
| **Total (prod + staging)**                                        | **~180–220**  |

Note how the shape differs from Hetzner: **~$64 of that is load balancer + NAT Gateway**, two line items that do no application work and cannot be scaled down.

### Option C — Google Cloud (Cloud Run)

Each always-on container is 0.5 vCPU / 1 GiB with instance-based (always-allocated CPU) billing, ≈ 730 h/month.

| Item                                                                        | Tier 1 (`europe-west1`, BE) | Tier 2 (`europe-west3`, DE) |
|-----------------------------------------------------------------------------|-----------------------------|-----------------------------|
| Cloud Run **worker pool** — importer, 1 instance, always on                 | ~29                         | ~40                         |
| Cloud Run **service** — BFF, `min-instances: 1` (JVM cold starts otherwise) | ~29                         | ~40                         |
| Cloud SQL — smallest shared-core + 40 GB SSD + public IPv4                  | ~45                         | ~45                         |
| Firebase Hosting / Cloud Storage + CDN for the SPA                          | ~1                          | ~1                          |
| Artifact Registry, Secret Manager, Cloud Logging                            | ~5                          | ~5                          |
| **Production subtotal ($)**                                                 | **~110**                    | **~130**                    |
| Staging (BFF scales to zero; smallest Cloud SQL)                            | ~30                         | ~35                         |
| **Total (prod + staging, $)**                                               | **~140**                    | **~165**                    |

The Frankfurt column is the price of the German *preference*; Belgium and the Netherlands are Tier 1 and satisfy the EU *requirement* for ~$25/month less.

### Option C2 — Google App Engine (for comparison only)

| Item                                                                  | $ / month |
|-----------------------------------------------------------------------|-----------|
| Flexible — importer, 1 vCPU / 1 GB, cannot scale below 1 instance     | ~48       |
| Flexible — BFF, 1 vCPU / 1 GB                                         | ~48       |
| Cloud SQL + registry + logging (as above)                             | ~50       |
| **Production subtotal**                                               | **~145**  |
| Standard-env alternative: 2 × F2 (512 MB) resident, **compute alone** | ~150      |
| Standard-env at a realistic 1 GB: 2 × F4 resident, **compute alone**  | ~290      |

### Option H — AWS Elastic Beanstalk `eu-central-1`

| Item                                                                           | $ / month    |
|--------------------------------------------------------------------------------|--------------|
| 2 × single-instance environments, `t4g.small` (2 vCPU / 2 GB, Graviton)        | ~28          |
| Public IPv4 × 2 ($0.005/h each — billed since 2024)                            | ~7           |
| EBS gp3, 2 × 15 GB                                                             | ~3           |
| RDS `db.t4g.small` PostgreSQL Single-AZ + 40 GB gp3 + backups                  | ~32          |
| S3 + CloudFront for the SPA                                                    | ~2           |
| Route 53, Secrets Manager, ECR, CloudWatch                                     | ~8           |
| **Production subtotal — no load balancer, no NAT Gateway**                     | **~80**      |
| Add an ALB in front of the BFF (TLS termination, health checks, zero-downtime) | +~24         |
| Staging (one shared `t4g.small` environment + `db.t4g.micro`)                  | ~35–50       |
| **Total (prod + staging)**                                                     | **~130–160** |

Beanstalk is roughly **half of Fargate** for the same application, and the saving is almost entirely the ALB and NAT Gateway that a single-instance environment
does not need. The trade is that we are back to sizing EC2 instances — a PaaS in workflow, an IaaS in responsibility.

### Option E — PaaS

Two always-on 1 GB containers plus a managed Postgres, prod only, list price ex-VAT. PaaS providers publish per-GB-RAM prices and size their databases
differently, so these are **estimates from vendor calculators and public price lists** — re-check with each vendor's own estimator before committing.

#### E1 — European providers

| Provider                 | Configuration                                                                           | Prod / month | + staging |
|--------------------------|-----------------------------------------------------------------------------------------|--------------|-----------|
| **Sliplane** 🇩🇪          | 1 × Medium server (3 vCPU / 4 GB) runs everything incl. a Postgres container            | €24          | €33       |
| **Clever Cloud** 🇫🇷      | 2 × S instances (1 GB) ≈ €40–60 + managed PostgreSQL ≈ €20–30                           | €60–90       | €95–130   |
| **Koyeb** 🇫🇷 (Frankfurt) | Pro $29 (incl. $10 compute) + 2 always-on small instances + Serverless Postgres ~$21    | ~$75         | ~$100     |
| **Northflank** 🇬🇧 (FRA)  | 2 × nf-compute-50 (0.5 vCPU / 1 GB) + Postgres nf-compute-100-2 (1 vCPU / 2 GB) $24     | ~$55         | ~$85      |
| **Scalingo** 🇫🇷          | 2 × L container (1 GB) @ €28.80 = €57.60 + PostgreSQL Starter (1–2 GB / 40 GB) ≈ €40–60 | €100–120     | €140–160  |
| **Upsun** 🇫🇷             | €9/project + €0.033/CPU-h + €0.013/GB-h × 2 apps + DB service + €10/user                | ~€140        | ~€180     |

Scalingo's €28.80 per GB-of-RAM container is the clearest illustration of the PaaS trade: the same gigabyte costs about €1 on Hetzner. What the €28.80 buys is
that nobody has to be awake for it.

#### E2 — US providers with EU regions

| Provider                  | Configuration                                                                           | Prod / month |
|---------------------------|-----------------------------------------------------------------------------------------|--------------|
| Fly.io (`fra`)            | 2 × shared-cpu-1x / 1 GB + Managed Postgres + volumes                                   | ~$50         |
| DigitalOcean App (`fra1`) | 2 × Basic (1 GB) + Managed Postgres 1 GB; static site free                              | ~$45–60      |
| Railway (EU West)         | 2 svc × (0.5 vCPU + 1 GB) @ $20/vCPU + $10/GB + Postgres + $20 Pro seat                 | ~$65         |
| Render (Frankfurt)        | 2 × Standard (1 CPU / 2 GB) @ $25 + Postgres Basic 1 GB; static site free               | ~$70         |
| **Heroku** (EU/Ireland)   | 2 × Standard-2X (1 GB) @ $50 + Postgres Standard-0 $50                                  | **~$150**    |
| Heroku, minimal           | 2 × Standard-1X (512 MB) @ $25 + Postgres Essential-2 $20 (shared, 4 h/mo downtime SLA) | ~$70         |

Heroku at production sizing costs the same as AWS Fargate while offering a fraction of the platform — it is the reference implementation of this category, not
the value option, and its EU region is Ireland (on AWS), so it is a US processor with EU residency.

### Summary — what this application costs per month

| Platform                                 | Type      | Managed PG | EU jurisdiction | Production | Prod + staging | Multiple of cheapest |
|------------------------------------------|-----------|------------|-----------------|------------|----------------|----------------------|
| **Hetzner Cloud + k3s** 🇩🇪               | IaaS      | ❌         | ✅              | **~€22**   | **~€30**       | 1×                   |
| Hetzner + Coolify/Dokku 🇩🇪 (E3)          | self-PaaS | ❌         | ✅              | ~€22       | ~€30           | 1×                   |
| Sliplane 🇩🇪                              | PaaS      | ❌         | ✅              | ~€24       | ~€33           | ~1.1×                |
| DigitalOcean App Platform (`fra1`)       | PaaS      | ✅         | ❌              | ~$45–60    | ~$70–85        | ~2.5×                |
| Fly.io (`fra`)                           | PaaS      | 🟡         | ❌              | ~$50       | ~$75           | ~2.5×                |
| Northflank 🇬🇧 (Frankfurt)                | PaaS      | ✅         | 🟡 adequacy     | ~$55       | ~$85           | ~2.8×                |
| Railway (EU West)                        | PaaS      | 🟡         | ❌              | ~$65       | ~$95           | ~3×                  |
| Render (Frankfurt)                       | PaaS      | 🟡         | ❌              | ~$70       | ~$105          | ~3.5×                |
| **Clever Cloud** 🇫🇷                      | PaaS      | ✅         | ✅              | ~€60–90    | ~€95–130       | ~3.5×                |
| Koyeb 🇫🇷 (Frankfurt)                     | PaaS      | ✅         | ✅              | ~$75       | ~$100          | ~3.3×                |
| **AWS Elastic Beanstalk**                | PaaS/IaaS | ✅ RDS     | ❌ (ESC option) | ~$80–100   | ~$130–160      | ~5×                  |
| **Scalingo** 🇫🇷                          | PaaS      | ✅ + PITR  | ✅              | ~€100–120  | ~€140–160      | ~5×                  |
| Google Cloud Run (Tier 1 EU / Frankfurt) | CaaS      | ✅         | ❌              | ~$110/$130 | ~$140/$165     | ~5×                  |
| Heroku (EU/Ireland), production sizing   | PaaS      | ✅         | ❌              | ~$150      | ~$190          | ~6×                  |
| Google App Engine (flexible)             | PaaS      | ✅         | ❌              | ~$145      | ~$185          | ~6×                  |
| AWS (Fargate)                            | CaaS      | ✅ RDS     | ❌ (ESC option) | ~$150      | ~$200          | ~6×                  |
| Upsun 🇫🇷                                 | PaaS      | ✅         | ✅              | ~€140      | ~€180          | ~6×                  |
| EKS / GKE managed Kubernetes             | CaaS      | ✅         | ❌              | ~$200      | ~$350          | ~10×                 |

Three honest caveats on this table. First, hyperscaler **free credits** distort year one — AWS and GCP both hand new accounts a few hundred dollars, which can
make the first 6–12 months look free and the thirteenth month look alarming; the table is steady-state. Second, the Hetzner number **excludes the labour** of
running PostgreSQL and k3s ourselves. If that is valued at even two hours a month, the gap to a PaaS narrows considerably — which is precisely the trade-off the
decision below turns on. Third, the PaaS rows are the *published* prices for a sizing we have not yet load-tested; every one of them bills per GB of RAM, so if
the JVMs need 2 GB rather than 1 GB the PaaS rows roughly double while the Hetzner row does not move at all.

**The shape of the answer to "is PaaS cheaper than CaaS?"**: yes, meaningfully — a European PaaS with managed Postgres lands at **€60–120/month** against **$
110–150** for Cloud Run or Fargate, and it deletes the ops burden rather than merely the YAML. But it is still **3–5× Hetzner**, and the cheapest way to get
PaaS *ergonomics* is not to buy a PaaS at all — it is Sliplane or Coolify on German hardware at ~€25/month, which buys push-to-deploy but explicitly does *not*
buy managed PostgreSQL.

---

## Decision

**Option A — Hetzner Cloud (Nuremberg/Falkenstein), k3s + Helm, provisioned with OpenTofu, PostgreSQL on a dedicated VM with `wal-g` PITR backups.**

Rationale, against the weighted criteria:

- **The Germany requirement is decisive and Hetzner satisfies it best.** It is a German company, in German data centres, under German jurisdiction, with a
  standard AVV — no SCCs, no CLOUD Act analysis, no sovereign-cloud premium. AWS's European Sovereign Cloud (GA since January 2026, Brandenburg) is the only
  candidate that matches this posture, and it costs more than regular AWS while offering fewer services.
- **Cost is not a rounding error at this scale — it is ~6× between the top and bottom of the table.** For a pre-revenue project, €30/month versus $200/month is
  the difference between "run staging and production properly" and "cut corners to keep the bill down". The saving directly funds the required staging stage,
  the domain, and an external uptime/monitoring service.
- **The workload wants exactly what a VM is.** `events-importer` is an always-on, single-instance, long-job scheduler (ADR-008). Every serverless pricing model
  in this comparison is optimised for the opposite shape, so we would pay a premium for elasticity the application cannot use.
- **The existing skills apply in full, and the Helm chart is on the backlog anyway.** Options B, C, and E all discard the Kubernetes/Helm layer; Option A is the
  only one where the planned "exercise the Helm chart on k3d/kind" work (TODO → Operations & Hardening) becomes the actual production deployment path.
- **Egress is included (20 TB/server).** For a public, image-heavy events site, metered egress is the most likely source of a surprise bill on any other option.
- **The PaaS layer was re-examined on its merits and does not beat this at *this* moment.** European PaaS is genuinely cheaper than the container platforms —
  Clever Cloud at ~€60–90/month is well under Cloud Run or Fargate, and Scalingo is the only non-Hetzner candidate that pairs EU jurisdiction with a managed
  Postgres that has PITR. But it is still 3–5× Hetzner, it discards the Helm work, and its Terraform story is thin-to-absent, which conflicts with the very next
  backlog item. The honest summary: **PaaS is what we buy when ops time becomes the binding constraint, not when euros are.** That trade is written into the
  fallback ranking below so it can be executed without re-litigating this ADR.

**The cost of this decision is that we own PostgreSQL and the k3s control plane.** That is the real trade, and it should not be glossed over: RDS and Cloud SQL
give automated backups, tested restores, PITR, and minor-version upgrades for free, and here we buy all of that with our own time. The mitigations below are
therefore **not optional** — they are the price of the decision.

### Deployment shape

```
                     Cloudflare (DNS, TLS, CDN, WAF/rate limiting — free plan)
                                        │
                            ┌───────────┴────────────┐
                            │   Hetzner CX33 (k3s)   │   Falkenstein / Nuremberg
                            │  ┌──────────────────┐  │
                            │  │ Traefik ingress  │  │
                            │  │   /      → web   │  │   nginx serving Vite dist/
                            │  │   /api   → bff   │  │   events-bff       (N replicas)
                            │  │  (admin: private)│  │   events-importer  (exactly 1)
                            │  └──────────────────┘  │
                            └───────────┬────────────┘
                                        │ private network (no public IP)
                            ┌───────────┴────────────┐
                            │   Hetzner CX23         │   PostgreSQL 18
                            │   + wal-g → Storage Box│   base backups + WAL, PITR
                            └────────────────────────┘
```

### Frontend hosting — containerise it, same origin as the API

`events-frontend` is a plain Vite SPA: `npm run build` produces a static `dist/`, and there is no SSR, no Nuxt, no server runtime. That means the *industry
default* would be a static host + CDN (Cloudflare Pages, Netlify, Vercel, S3+CloudFront) — for a generic SPA that is the right answer, and it is usually free.

**For this project we should still ship it as a Docker image** (multi-stage: `node` builds, `nginx`/`Caddy` serves `dist/`), deployed by the same Helm chart
behind the same ingress. The reasons are specific rather than dogmatic:

1. **Same origin removes CORS entirely.** Routing `/` → frontend and `/api` → BFF through one ingress means no preflight requests, no `Access-Control-*`
   configuration to keep in sync across three environments, and — importantly for the planned authentication work (TODO 🟠 Next) — session cookies are
   first-party, so `SameSite` and third-party-cookie restrictions stop being a problem. This alone justifies the choice.
2. **It keeps the Germany requirement intact.** Cloudflare Pages, Netlify, and Vercel are US companies serving from a global edge; every one of them adds a
   processor and a jurisdiction question that a container in Falkenstein does not.
3. **One pipeline, one rollback.** Same registry, same `helm upgrade`, same `helm rollback`, same environment promotion. A second deploy mechanism for one
   static bundle is not worth the split.
4. **The cost is negligible.** nginx serving a few MB of assets runs comfortably in 16–32 MB of RAM.

Three things to get right, because a containerised SPA is easy to ship subtly broken:

- **History-mode fallback.** vue-router uses HTML5 history mode, so nginx needs `try_files $uri $uri/ /index.html;` — without it, deep links and page refreshes
  return 404.
- **Cache headers.** Vite content-hashes everything under `/assets/`, so serve those with `Cache-Control: public, max-age=31536000, immutable` and serve
  `index.html` with `no-cache`. Get this backwards and users either never see deploys or re-download the bundle constantly.
- **Build-time config.** Vite inlines `import.meta.env.*` at build time, so a per-environment API URL would mean one image per environment. Avoid this by having
  the SPA call a **relative** `/api` path — the image then becomes environment-agnostic and the identical artifact promotes from staging to production, which is
  what we want anyway.

**On a PaaS, invert this.** The reasoning above holds because a container on Hetzner costs cents. On a per-GB-RAM PaaS an nginx serving 3 MB of assets is billed
like any other container — €28.80/month on Scalingo, $25 on Render — for work a CDN does for free. So if any Option E fallback is taken, host the SPA as a
static site instead (Render, DigitalOcean and Northflank include static sites at no charge; Scalingo and Clever Cloud do not, so use Cloudflare Pages or a
Hetzner Object Storage bucket + Cloudflare) and pay the CORS cost: an explicit `Access-Control-Allow-Origin` allowlist on the BFF per environment, plus
`SameSite=None; Secure` cookies once authentication lands. That is a real but bounded amount of configuration, and it is the correct trade at PaaS prices —
roughly €30/month of savings against an hour of CORS setup.

Put **Cloudflare in front** (free plan, proxied DNS) for TLS, edge caching of the static assets, and rate limiting / DDoS protection — which also makes progress
on the "Protect the public BFF API (rate limiting, DDoS)" backlog item. Note the residency nuance: Cloudflare terminates TLS at its edge, so if strictly
German-only processing is ever required, either drop Cloudflare's proxy mode or buy its EU data-localisation add-on.

### When to revisit

This decision should be **reopened**, not defended, if any of these become true:

- The project takes on **a team, paying customers, or a compliance obligation** (SOC 2, an enterprise customer's security review) — managed services and audit
  trails start earning their price.
- **Database operations become a recurring source of pain or fear** — the first restore that does not work is the signal. That is fallback 1 below: keep Hetzner
  compute, move Postgres to a managed EU provider. The next step up is fallback 3 — Clever Cloud or Scalingo, and hand over the compute as well.
- **Evenings spent on the platform exceed evenings spent on the product for two months running.** That is the concrete trigger for the PaaS fallback; €40–90 a
  month is a fair price for that time, and the decision should be made on that evidence rather than pre-emptively either way.
- **Uptime requirements harden** past what a single-region, single-node k3s cluster can honestly promise.
- The roadmap pulls in **managed building blocks** — README lists Elasticsearch as "maybe later", and TODO lists Keycloak/auth. If we end up wanting managed
  search, managed identity, and managed queues, the hyperscaler discount on *integration effort* starts to outweigh the compute premium.

**The ranked fallbacks**, in the order they should be reached for. This list is not dead prose: the decision was accepted knowing that its one serious risk is
Postgres operations, and fallback 1 exists so that risk has a cheap answer that does not require reopening the ADR.

1. **Keep Hetzner, move PostgreSQL to a managed EU provider** (Option G) — the *first* thing to try if the Postgres-ops risk materialises, because it is the only
   fallback that addresses that risk without discarding anything: the Helm chart, the OpenTofu configuration, the containerised SPA and the same-origin `/api`
   arrangement all survive, and the application changes by one connection string. **Aiven** is EU-owned (Finnish) and keeps the jurisdiction property intact at
   ~€60–80/month; Neon and Supabase are cheaper but are US companies with EU regions, so they trade jurisdiction for price. The cost is a second vendor, a second
   DPA, and cross-provider latency on every query — which matters more here than usual, because R2DBC upserts are chatty. Benchmark before committing.
2. **Sliplane, or Coolify/Dokku self-hosted on Hetzner** (Option E3) — if the objection is "I don't want to write a Helm chart", not "I don't want to run a
   database". Same ~€25–35/month, push-to-deploy, German hardware, and it stays on the Hetzner escape path. It does **not** solve Postgres ops, so it composes
   with fallback 1 rather than replacing it.
3. **Clever Cloud, or Scalingo** (Option E1) — if the objection is ops time in general, compute as well as database. French SAS, EU jurisdiction and EU data
   centres, managed PostgreSQL with automated backups and PITR, native Spring Boot support. ~€60–120/month for production. Scalingo is the safer, more
   Heroku-like product and has a SecNumCloud-qualified region; Clever Cloud is roughly a third cheaper. Taking this fallback **inverts the frontend-hosting
   decision** — the SPA moves to a static host and the CORS cost comes back; see §Frontend hosting.
4. **GCP Cloud Run + Cloud SQL** (Option C) — the best hyperscaler fit for this shape: no load-balancer line item, free scale-to-zero staging, and worker pools
   now match ADR-008's always-on single-instance scheduler natively. Deploy to `europe-west1`/`europe-west4` (Tier 1) unless German soil is worth ~$25/month.
5. **AWS Elastic Beanstalk** (Option H) — if AWS is wanted for career or ecosystem reasons but Fargate's floor is not. Roughly half of Fargate at ~$80–100/month
   because a single-instance environment needs neither an ALB nor a NAT Gateway, and RDS is the best managed Postgres in this document.
6. **AWS Fargate** (Option B) last for *this* stage of the project, but first the moment breadth of managed services or enterprise credibility becomes the
   binding constraint.

Explicitly **not** recommended at any position: **Heroku** (production sizing costs as much as Fargate for a fraction of the platform, and its EU region is
Ireland on AWS) and **App Engine** (more expensive than Cloud Run, region-locked per project, and the direction of travel inside Google is away from it).

### On "AWS is the most flexible and the standard, isn't it?"

Both halves are true, and neither is decisive here.

**"The standard"** — yes, by market share (~28–31 %) and by enterprise default. That is a strong reason to *know* AWS and a weak reason to *host on it*. Nothing
in this application needs a service that only AWS has.

**"The most flexible"** — yes, in breadth of catalogue. But flexibility at AWS is sold as *provisioned capacity with an hourly floor*. An ALB, a NAT Gateway,
and an RDS instance bill ~$96/month combined before the application does anything; that is the price of options we would not exercise. Flexibility we pay for
monthly and never use is not flexibility, it is overhead.

There is also a quieter point: for a two-service application, ECS+Fargate is the *least* ergonomic of the managed-container platforms compared here. Cloud Run
and Container Apps give an HTTPS endpoint from an image; ECS wants task definitions, target groups, listener rules, execution roles, and log groups — all of
which we would author and maintain in Terraform. AWS's flexibility is real, but at this size it is charged in both euros and YAML.

The honest summary: **AWS is the right answer to a different question** — one with a team, a compliance requirement, or a service catalogue to draw on. Choosing
Hetzner now does not close that door; the application is containers and Postgres, and the Helm chart is the portable artifact.

---

## Consequences

- **Positive**: Lowest total cost by a wide margin (~€30/month for production *and* staging), so the required staging stage is affordable. Strongest GDPR/data
  residency position of any candidate. Docker, Kubernetes, Helm, and Terraform skills apply directly, and the planned Helm chart becomes the production
  deployment path. Egress is included, removing the most common bill-shock vector. Always-on containers suit ADR-008's scheduler without workarounds. Low
  lock-in — the workload is containers, Kubernetes manifests, and Postgres.
- **Negative**: **We own PostgreSQL.** Backups, PITR, restore verification, and minor-version upgrades are ours. We also own the k3s control plane, OS patching,
  and node upgrades. No managed observability. Single region and, initially, a single node — a node failure is an outage. Email-only support.
- **Backups are the load-bearing mitigation** (`wal-g` or `pgBackRest` streaming to Hetzner Storage Box, plus Hetzner server snapshots). A **restore drill must
  be part of the go-live checklist and repeated on a schedule** — an untested backup is not a backup. This is the single highest-risk item created by this ADR,
  and the ADR was accepted on the understanding that a failed drill triggers fallback 1 (managed EU Postgres) rather than a round of heroics.
- **Single-instance importer**: the Helm chart must set `replicas: 1` with `strategy: Recreate` for `events-importer` so a rolling deploy never runs two
  schedulers. Multi-replica operation stays blocked on the `SELECT … FOR UPDATE SKIP LOCKED` work noted in ADR-008.
- **Admin API exposure**: `events-importer`'s admin endpoints must not be routed publicly by the ingress — cluster-internal service only, reachable via
  `kubectl port-forward` or, later, behind the planned authentication. **The planned admin frontend inherits this**: at launch it runs locally against a
  port-forwarded admin API and is not deployed at all; the moment it *is* deployed, it needs edge access control ahead of the application-level auth work —
  Cloudflare Access on the free plan is the cheapest fit given Cloudflare is already in front, with an ingress IP allowlist or basic-auth middleware as
  alternatives. Do not route either the admin UI or the admin API publicly on the assumption that "nobody knows the URL".
- **IaC**: use the `hetznercloud/hcloud` OpenTofu/Terraform provider for servers, networks, firewalls, and volumes; keep state in Hetzner Object Storage (S3
  API) or Terraform Cloud. This unblocks the "Infrastructure as code" backlog item.
- **CI/CD**: GitHub Actions cannot use OIDC against Hetzner, so deploys authenticate with a scoped kubeconfig or deploy key held as a repository secret, rotated
  deliberately. This is a genuine step down from AWS/GCP OIDC and should be treated as such.
- **Observability is now our problem**: budget for either a self-hosted `kube-prometheus-stack` + Grafana (fits the "Dashboard for analysing the data" backlog
  item) or an external SaaS free tier. Alerting must exist before launch, not after the first outage.
- **Frontend**: adds a `Dockerfile` and an nginx config to `events-frontend/`, and the SPA must call the API via a relative `/api` path so one image serves
  every environment.
- **Cost re-check**: Hetzner raised prices in 2026 and may again. Re-verify the numbers in this ADR at go-live and revisit annually.
- **Both exits are pre-decided, not pre-committed**, and they are different sizes. If *the database* is the problem, fallback 1 moves Postgres to a managed EU
  provider and nothing else changes. If *ops time in general* is the problem, the move is Clever Cloud or Scalingo (EU jurisdiction, managed Postgres with PITR),
  and the SPA moves to a static host at that point rather than staying a container — see the fallback ranking. Keeping the application to "a Docker image plus a
  Postgres URL", with no Kubernetes-specific code, is what keeps both exits cheap; the Helm chart is the only artifact thrown away, and only by the second one.
- **Follow-ups unblocked** — the rest of the `v0.2 — Deployable` milestone, which was blocked on this decision and is not any more:
  [#259](https://github.com/enorm-labs/event-checker/issues/259) register `event-junkie.de` ·
  [#260](https://github.com/enorm-labs/event-checker/issues/260) the OpenTofu configuration ·
  [#261](https://github.com/enorm-labs/event-checker/issues/261) the Helm chart ·
  [#262](https://github.com/enorm-labs/event-checker/issues/262) containerise the frontend ·
  [#263](https://github.com/enorm-labs/event-checker/issues/263) exercise both on k3d ·
  [#264](https://github.com/enorm-labs/event-checker/issues/264) the release and deploy workflows ·
  [#265](https://github.com/enorm-labs/event-checker/issues/265) the staging stage. The go-live checklist (legal, security, SEO, monitoring, alerting,
  dashboards, backups, recovery) follows in `v1.0`.

## References

- [Hetzner Cloud price adjustment, 15 June 2026](https://docs.hetzner.com/general/infrastructure-and-availability/price-adjustment/) — current CX/CPX/CAX/CCX
  pricing
- [Hetzner Cloud](https://www.hetzner.com/cloud/) · [
  `hetznercloud/hcloud` Terraform provider](https://registry.terraform.io/providers/hetznercloud/hcloud/latest/docs)
- [AWS Fargate pricing](https://aws.amazon.com/fargate/pricing/) · [Amazon VPC pricing (NAT Gateway, IPv4)](https://aws.amazon.com/vpc/pricing/)
- [AWS launches the European Sovereign Cloud, 15 January 2026](https://press.aboutamazon.com/aws/2026/1/aws-launches-aws-european-sovereign-cloud-and-announces-expansion-across-europe)
- [Google Cloud Run pricing](https://cloud.google.com/run/pricing) · [Cloud SQL pricing](https://cloud.google.com/sql/pricing)
- [AWS Elastic Beanstalk pricing](https://aws.amazon.com/elasticbeanstalk/pricing/) (the service is free; you pay for
  EC2/RDS/ELB) · [Elastic Beanstalk platform release schedule](https://docs.aws.amazon.com/elasticbeanstalk/latest/dg/platforms-schedule.html) — AL2 branches
  retire 30 June 2026
- [App Engine pricing](https://cloud.google.com/appengine/pricing) · [Deploy worker pools to Cloud Run](https://cloud.google.com/run/docs/deploy-worker-pools) —
  the always-on, manually-scaled primitive that fits
  ADR-008 · [Migrate from App Engine to Cloud Run](https://cloud.google.com/run/docs/migrate/from-app-engine-to-cloud-run)
- European
  PaaS: [Scalingo pricing](https://scalingo.com/pricing) · [Clever Cloud pricing](https://www.clever.cloud/pricing/) · [Upsun pricing](https://docs.upsun.com/administration/pricing.html)
  · [Koyeb pricing](https://www.koyeb.com/pricing) · [Sliplane pricing](https://sliplane.io/) · [Northflank pricing](https://northflank.com/pricing)
- [Fly.io pricing](https://fly.io/docs/about/pricing/) · [Railway pricing](https://docs.railway.com/reference/pricing/plans) · [Render pricing](https://render.com/pricing)
  · [Heroku pricing](https://www.heroku.com/pricing/) · [DigitalOcean App Platform pricing](https://www.digitalocean.com/pricing/app-platform)
- [Coolify](https://coolify.io/) — self-hosted PaaS layer for Option E3
- [DigitalOcean managed databases pricing](https://www.digitalocean.com/pricing/managed-databases) · [Neon pricing](https://neon.com/pricing)
- [Cloud market share 2026 (Synergy Research, via Statista)](https://www.statista.com/chart/18819/worldwide-market-share-of-leading-cloud-infrastructure-service-providers/)
- [ADR-005 — Migrations owned by the importer](ADR-005_MIGRATIONS_OWNED_BY_IMPORTER.md)
- [ADR-008 — Import job scheduling](ADR-008_IMPORT_JOB_SCHEDULING.md) — the single-instance / always-on constraint
- [The `v0.2 — Deployable` milestone](https://github.com/enorm-labs/event-checker/milestones) — the path to go-live
