# ADR-012: Cloud Platform & Hosting

## Status

**Accepted — Option A: Hetzner Cloud (Nuremberg/Falkenstein), k3s + Helm, OpenTofu, PostgreSQL on a dedicated VM with `wal-g` PITR backups.**

**Amended: no Cloudflare.** DNS and TLS are in-house — DNS served by Hetzner, and **Traefik terminates TLS in the cluster** via cert-manager
([#412](https://github.com/enorm-labs/event-junkie/issues/412)). Everything below about compute, database, cost and
fallbacks still holds. Only the edge changed. The reasoning, and what it cost, is in §The Cloudflare amendment.

**All prices in this document are indicative.** They come from one survey, and cloud list prices moved repeatedly after
it. The _shape_ of the comparison survives a 30% move. The arithmetic does not. What the platform actually costs today
is [ops/COSTS.md](../ops/COSTS.md).

## Context

Event Junkie is a Berlin music-events guide. This decision came **before the first deploy**. It chose the platform
that the OpenTofu and the Helm chart are written against. That is why the alternatives are recorded at length: a
switch later costs real work.

### What actually has to run

Four deployables today, derived from the modules in this repo, plus one more that the backlog makes near-certain:

| Component         | Shape                                                             | Runtime demand                                                     |
| ----------------- | ----------------------------------------------------------------- | ------------------------------------------------------------------ |
| `events-bff`      | Spring Boot 4 / WebFlux / R2DBC, read-only public API (port 8080) | Stateless, horizontally scalable, JVM ≈ 512 MB–1 GB heap           |
| `events-importer` | Spring Boot 4 / WebFlux / R2DBC + admin API (port 8081)           | **Always-on, effectively single-instance** — see below             |
| `events-frontend` | Vue 3 + Vite **SPA** — `npm run build` emits a static `dist/`     | No server runtime of its own; static files + a router fallback     |
| PostgreSQL 18     | The only stateful component; owns all event/venue/artist data     | ~2 vCPU / 4 GB, tens of GB, needs backups + point-in-time recovery |
| _admin frontend_  | **Planned** (TODO 🟠) — a second static SPA over the admin API    | Static files, one user, **must not be publicly reachable**         |

**On the planned admin frontend.** TODO lists an admin UI to operate the importers and curate the data. It covers
import status, import configuration, a data-quality overview and manual event entry. It is not built yet, but it is
close enough to change two things here. So it is priced and designed for, rather than discovered later:

- **It is a fifth deployable, and its cost is not uniform across the options.** On Hetzner or any container platform it is another nginx serving a few MB —
  noise. On a **per-GB-RAM PaaS it is another billed container** — about €25–30/month on Scalingo, $25 on Render.
  That is a second reason those options want their SPAs on a static host rather than in a container. On Cloud Run it is
  close to free, because admin traffic is one person and an admin service can genuinely scale to zero.
- **It forces the admin-API exposure question that would otherwise wait.** The importer's admin API must not be public
  (below), and the current answer is `kubectl port-forward`. A browser-based admin UI can run locally against that
  port-forward. That is free, fine for one developer, and the launch answer. Deployed instead, it needs access control
  _at the edge_ before the planned authentication work lands. Platforms differ here. Cloud Run has IAM/IAP for exactly
  this. Hetzner + Traefik needs an ingress IP allowlist or a basic-auth middleware. Most PaaS options offer nothing
  below the application layer.

Three properties of `events-importer` constrain the platform choice more than anything else:

1. **It is a scheduler, not a request handler.** [ADR-008](ADR-008_IMPORT_JOB_SCHEDULING.md) runs a `@Scheduled(fixedDelay = 60s)` tick that queries
   `event_source` for due venues. A wall-clock tick every minute cannot work with request-driven scale-to-zero. Cloud
   Run request-billing, Lambda and Container Apps all scale to zero, and the tick never fires without a request.
2. **It must be exactly one instance.** ADR-008 is explicit that the `status = 'RUNNING'` exclusion "is not a true
   lock", and that multi-instance operation needs `SELECT … FOR UPDATE SKIP LOCKED` first. A platform whose default
   deploy strategy runs old and new replicas together needs `maxSurge: 0` or `Recreate` semantics. The alternative is
   to accept the idempotency argument in ADR-008.
3. **Its jobs are long.** A heavy importer (Badehaus) fetches about 90 throttled detail pages and runs **over a
   minute**. The staleness guard is 30 minutes. Request timeouts on a serverless container platform are a real
   constraint. Cloud Run caps at 60 min, App Runner at 120 s per request. ADR-008 already made a manual trigger
   fire-and-forget, which softens that. A short hard ceiling still rules a platform out.

Also relevant: [ADR-005](ADR-005_MIGRATIONS_OWNED_BY_IMPORTER.md) puts Flyway in the importer. The importer must
therefore reach the database before the BFF serves traffic. And the importer's admin API **must not be publicly
reachable**.

### Non-functional context

- **Scale is small and will stay small for a while.** Eight venues live today and about 40 are planned. The public API
  is read-mostly and cacheable. Realistic launch traffic is well under 100 GB egress/month.
- **One developer.** Ops time is the scarcest resource, scarcer than euros. But euros are not free either. A
  €200/month bill for a pre-revenue side project is its own kind of pressure.
- **A separate staging stage is required** (TODO 🔴 Now). On every platform with per-hour floors this roughly doubles the bill, so "cost of the second
  environment" is a first-class criterion, not an afterthought.
- **EU / Germany hosting is a hard requirement.** The product is German (`event-junkie.de`) and the users are in
  Berlin. The data includes third-party website content. Once accounts land (TODO 🟠 Next), it also includes personal
  data under GDPR.
- **Existing skills**: Docker, Kubernetes, Helm, Terraform. TODO already plans a Helm chart and to exercise it locally on k3d/kind, so a Kubernetes-shaped
  target reuses work that is going to happen anyway.

### Criteria

| #   | Criterion                           | Weight | Why it matters here                                                                                         |
| --- | ----------------------------------- | ------ | ----------------------------------------------------------------------------------------------------------- |
| 1   | Fit for always-on JVM + scheduler   | High   | ADR-008's tick rules out scale-to-zero for the importer; JVM cold starts (2–5 s) hurt request-billed models |
| 2   | Managed PostgreSQL (backup/PITR)    | High   | The only stateful thing we own; losing it loses everything. Self-managing it is the main hidden cost        |
| 3   | Total cost at _this_ scale          | High   | Pre-revenue. Per-hour floors (LB, NAT, DB) dominate — not per-request pricing                               |
| 4   | Cost of a second (staging) stage    | High   | Explicitly required; on hyperscalers it is nearly a second full bill                                        |
| 5   | EU / German data residency          | High   | GDPR, German users, German domain; DPA/AVV and jurisdiction matter                                          |
| 6   | Ops burden                          | High   | Who patches the OS, the DB, the K8s control plane — paid in evenings, not invoices                          |
| 7   | Reuse of Docker/K8s/Helm/Terraform  | Med    | Skills already held; a Helm chart is already on the backlog                                                 |
| 8   | Terraform/OpenTofu provider quality | Med    | IaC is the next backlog item after this one                                                                 |
| 9   | CI/CD integration (GitHub Actions)  | Med    | Build workflows already exist; want OIDC over long-lived keys                                               |
| 10  | Egress predictability               | Med    | Metered egress is the classic source of bill shock                                                          |
| 11  | Observability included              | Med    | TODO wants monitoring/alerting/dashboards; "included" beats "assemble"                                      |
| 12  | Lock-in / exit cost                 | Med    | A managed-container + managed-Postgres app is portable; proprietary glue is not                             |
| 13  | Scaling headroom                    | Low    | Nothing here needs to scale for a long time, but the door shouldn't be nailed shut                          |
| 14  | Career / CV signal                  | Low    | Real, but it is a tie-breaker, not a driver                                                                 |

Criterion #5 asks for two different things. **Data residency** means the bytes sit in the EU, which an EU region at
any US provider satisfies under SCCs. **Jurisdiction** means the contracting entity is European, so no CLOUD Act
analysis is needed. Only Hetzner, Scalingo, Clever Cloud, Upsun, Koyeb, Sliplane and AWS's European Sovereign Cloud
satisfy that. Residency is the hard requirement. Jurisdiction is the strong preference. The tables below score them
separately.

### Which platforms are actually popular?

Worth stating plainly, because "popular" and "right for this" are different questions. Per Synergy Research for 2026,
the global cloud-infrastructure market is roughly **AWS ~28–31 %, Microsoft Azure ~21–25 %, Google Cloud ~11–14 %**.
Together that is about two-thirds of the market. Everything else (Alibaba, Oracle, IBM, DigitalOcean, Hetzner, OVH,
Scaleway, and the PaaS layer of Fly/Render/Railway/Vercel) shares the remaining third.

So yes — **AWS is the market leader and the enterprise default.** That is a fact about procurement, hiring, and breadth
of catalogue. It is not, by itself, an argument that AWS is the right home for two containers and a small Postgres.
This ADR keeps the two apart.

---

## Candidate options

### Option A — Hetzner Cloud (Nuremberg / Falkenstein) + k3s

German company (Gunzenhausen), German data centres, ISO 27001, standard AVV. Compute is plain VMs. We run **k3s** on
them with the official `hcloud-cloud-controller-manager` and CSI driver. We deploy with the Helm chart TODO already
plans, and provision with the `hetznercloud/hcloud` Terraform provider. PostgreSQL runs on its **own VM**, outside the
cluster, with `wal-g`/`pgBackRest` streaming WAL to Hetzner Object Storage or a Storage Box.

- **Pros**: By far the cheapest — a factor of 5–6 versus AWS for this workload. 20 TB egress **included** per server, so no bandwidth bill shock. Cleanest GDPR
  story of any candidate (German entity, German jurisdiction, no CLOUD Act exposure). Every existing skill applies directly. A second (staging) environment
  costs
  ~€7/month, so the required staging stage is genuinely affordable. Always-on is the default, so ADR-008's scheduler needs no workarounds.
- **Cons**: **No managed PostgreSQL** — backups, PITR, restore drills, minor-version upgrades and disk-full are ours. We also own the k3s control plane, OS
  patching, and node upgrades. No managed observability (Prometheus/Grafana or an external SaaS is on us).
  Single-region. DDoS protection is basic — Hetzner has volumetric protection, but no WAF or rate-limiting layer.
  Support is email-only.
- **Hetzner raised cloud prices on 15 June 2026** (CX23 €3.99 → €5.49, and the dedicated CCX/CPX lines up to 2–3×).
  Still the cheapest by a wide margin, but the "Hetzner never raises prices" assumption is dead and nothing should
  plan around it.

### Option B — AWS (ECS Fargate + RDS + ALB + CloudFront/S3), `eu-central-1`

The market-leading, maximal-optionality choice. AWS's own PaaS, Elastic Beanstalk, has a materially different cost and
ops profile. **Option H** evaluates it separately.

- **Pros**: the largest service catalogue by far. Managed Elasticsearch/OpenSearch (README lists it as "maybe later"),
  SQS, EventBridge or Cognito for the planned auth are all one `terraform apply` away. Best-in-class Terraform provider
  and by far the most documentation, examples, and hiring signal. RDS is a genuinely excellent managed Postgres
  (automated backups, PITR, one-click restore, Multi-AZ when we want it). GitHub Actions OIDC is first-class. Frankfurt
  (`eu-central-1`) satisfies EU residency. The **AWS European Sovereign Cloud** is generally available since
  **15 January 2026**, with its first region in Brandenburg, Germany. It has a separate German legal entity,
  EU-resident staff, and independent IAM, billing and DNS. That is the strongest answer available to the CLOUD Act
  concern, at a price premium and with a smaller service set.
- **Cons**: **The fixed floor is the problem, not the variable cost.** An ALB bills ~$24/month whether or not anyone
  visits. A NAT Gateway adds ~$38/month before a byte moves. RDS bills per hour, and Fargate has no scale-to-zero.
  None of that is elastic at our size, so we would pay for capacity nobody uses. ECS + Fargate also needs far more
  assembly than Cloud Run or Container Apps: task definitions, target groups, listener rules, execution roles, log
  groups. It is therefore the most Terraform to write and own. Egress is metered at ~$0.09/GB, plus a further
  ~$0.052/GB if it traverses NAT. Staging nearly doubles the bill.

### Option C — Google Cloud (Cloud Run or App Engine + Cloud SQL), `europe-west3` (Frankfurt)

The best hyperscaler _ergonomics_ for exactly this workload shape.

- **Pros**: Cloud Run is the nicest managed-container experience of the three. You push an image and you get an HTTPS
  endpoint with a managed certificate. **There is no load-balancer line item**, which removes ~$24/month of AWS's
  floor outright. Scale-to-zero makes the **staging** environment nearly free, which directly serves criterion #4.
  Cloud SQL is a solid managed Postgres. Cloud Build/Artifact Registry and GitHub OIDC integrate cleanly.
- **Cloud Run _worker pools_ (GA in 2026) close the ADR-008 gap.** A worker pool is a non-HTTP, always-on, **manually
  scaled** Cloud Run deployment for pull and background work. Its instances run continuously, with no request-based
  billing and no per-request fee. Google prices always-allocated CPU ~25 % and memory ~20 % below the request-billed
  rates. `events-importer` fits this primitive exactly. `@Scheduled` ticks fire because the instance never stops, and
  "manually scaled to 1" states ADR-008's single-instance constraint in platform-native terms. That is a real
  improvement over `min-instances: 1` on a service, which is the awkward alternative. The BFF stays an ordinary Cloud
  Run service.
- **Cons**: **Frankfurt is a Tier 2 Cloud Run region.** CPU costs $0.0000336/vCPU-s and memory $0.0000035/GiB-s,
  against $0.000024 and $0.0000025 in Tier 1. So `europe-west3` costs roughly a third more than `europe-west1`
  (Belgium) or `europe-west4` (Netherlands) for identical containers. Both Tier 1 alternatives are still EU territory.
  The German _preference_ costs about $30/month here, and the EU _requirement_ costs nothing. Cloud SQL has awkward
  small-instance economics: a public IPv4 alone is ~$9.57/month idle, and a private IP needs Direct VPC egress or a
  connector. Google's product-deprecation reputation is a real, if often overstated, planning risk. Same CLOUD Act
  posture as AWS, and no German-jurisdiction sovereign offering equivalent to AWS's ESC.

#### C2 — App Engine, evaluated because it was asked for, and rejected

App Engine is Google's original PaaS and can run a Spring Boot fat JAR, so it belongs in a PaaS evaluation. It loses to Cloud Run on every axis that matters
here:

- **Standard environment** bills per _resident instance-hour_ by instance class: F1/B1 $0.05, F2/B2 $0.10, F4/B4
  $0.20, F4_1G $0.30 per hour. Those are US rates, and `europe-west3` carries a regional premium on top. Our JVMs need
  ≥512 MB and the scheduler needs a resident instance, which means `basic` or `manual` scaling. That is **no
  scale-to-zero and no free-quota relief**: the free tier is 28 instance-hours/day, enough for one tiny instance. F2 ≈
  **$73/month** per always-on service and F4 ≈ **$146/month**. That is 2–5× Cloud Run for the same container.
- **Flexible environment** bills $0.0526/vCPU-hour + $0.0071/GB-memory-hour (again plus a European premium) and
  **cannot scale below one instance**. One 1 vCPU / 1 GB service is ≈ **$44–48/month**, so bff + importer ≈
  **$95/month** before the database. Deploys also take minutes, because each one rolls a VM.
- **Structural annoyances**: one App Engine application per GCP project, and its **region is fixed for the life of the
  project**. To move region, you make a new project. Google now steers new workloads to Cloud Run and ships
  `gcloud app migrate-to-run` tooling in that direction. The first-generation runtimes, Java 8 among them, were
  deprecated on 31 January 2026.

**Verdict**: within Google Cloud, Cloud Run + worker pools strictly dominates App Engine for this application. App Engine is not carried into the comparison
tables except in the cost summary, where it is shown to document the gap.

### Option D — Azure (Container Apps + PostgreSQL Flexible Server), Germany West Central

- **Pros**: Container Apps has Cloud Run-like ergonomics with free built-in ingress, and it is KEDA-based so cron-style scaling is native. Azure Database for
  PostgreSQL **Flexible Server** has the friendliest small-instance pricing of the three hyperscalers (B-series burstable, ~$13–15/month for B1ms). Germany West
  Central is a full region.
- **Cons**: the smallest third-party ecosystem of the three for this stack. Terraform's `azurerm` provider is good,
  but the resource model is chattier. No
  compelling advantage over Option C for us, and the same jurisdictional posture. Included mainly for completeness.

### Option E — Platform-as-a-Service

The PaaS layer can satisfy both "one developer, no evenings for ops" and "EU data residency", without hyperscaler
floor costs. So it splits into three genuinely different families here, rather than one "PaaS" option.

They all share the same shape: push a Dockerfile or a Git branch, and get a URL, TLS and logs. Most of them add a
managed Postgres with automated backups. All of them support **always-on containers**, so ADR-008's scheduler is
unproblematic on every candidate below. They also share two weaknesses. **None of the Docker/K8s/Helm skills carry
over past the Dockerfile**, because hiding that layer is the entire product. And their Terraform providers are thin or
absent, which weakens criterion #8.

#### E1 — European PaaS (preferred family if a PaaS is chosen)

| Provider         | Jurisdiction       | Regions                              | Managed Postgres              | Notes                                                            |
| ---------------- | ------------------ | ------------------------------------ | ----------------------------- | ---------------------------------------------------------------- |
| **Scalingo**     | 🇫🇷 SAS, Strasbourg | `osc-fr1`, `osc-secnum-fr1` (France) | ✅ Starter/Business, **PITR** | "Heroku, but European". ISO 27001, HDS, SecNumCloud region       |
| **Clever Cloud** | 🇫🇷 SAS, Nantes     | Paris, Gravelines + EU partners      | ✅ PostgreSQL add-on          | Native Spring Boot build pack, per-second billing, ISO 27001     |
| **Upsun**        | 🇫🇷 Platform.sh SAS | Multiple EU regions                  | ✅ as a project "service"     | Git-branch-per-environment; best staging story, worst price/perf |
| **Koyeb**        | 🇫🇷 SAS             | Frankfurt (DE), Paris, others        | ✅ serverless (Neon-style)    | Scale-to-zero; Frankfurt satisfies the German preference         |
| **Sliplane**     | 🇩🇪 GmbH            | Germany (on Hetzner), Finland        | ❌ a DB is just a container   | Flat per-server price, unlimited containers, free egress         |
| **Northflank**   | 🇬🇧 UK (adequacy)   | Frankfurt, NL, Zurich, EU West       | ✅ Postgres addon             | Most Kubernetes-like of the set; BYOC possible                   |

- **Pros**: ops burden collapses to "watch the dashboard". **Besides Hetzner, Scalingo and Clever Cloud are the only
  candidates here that combine EU jurisdiction with a managed PostgreSQL.** That database does automated backups and
  PITR. So they remove the single biggest drawback of Option A, and import no CLOUD Act question. Scalingo's
  SecNumCloud region is a stronger sovereignty claim than anything AWS or Google offers outside AWS's ESC. Prices sit
  clearly below the hyperscalers.
- **Cons**: roughly **3–5× Hetzner** (see pricing below). Per-GB-RAM container pricing is how a PaaS makes money, and
  two always-on JVMs at 1 GB each is the shape it charges most for. Small ecosystems: fewer StackOverflow answers,
  thinner Terraform providers, and a bus factor question a hyperscaler does not have. Sliplane is the odd one out:
  German, cheapest, but with no managed database. It gives you PaaS _ergonomics_ only. So it does not retire the
  Postgres-ops risk that motivates a PaaS at all.

#### E2 — US PaaS with EU regions: Heroku / Fly.io / Render / Railway / DigitalOcean App Platform

- **Pros**: the most mature developer experience of the lot, Heroku above all. Every other platform on this page
  imitates it. Render has Frankfurt, Fly has `fra`, and Railway has EU West (Amsterdam). DigitalOcean App Platform
  has `fra1`, and Heroku's Common Runtime has an EU region (Ireland, on AWS).
- **Cons**: all are **US companies**, so EU _residency_ is satisfiable but EU _jurisdiction_ is not. SCCs make that
  acceptable. It is still weaker than a German or French provider against the stated preference, and it is one more
  processor in the GDPR record. **Heroku is also the most expensive PaaS here.** Production dynos cost $50/month each
  at 1 GB (Standard-2X), and a Standard-tier Postgres another $50/month. That prices out near AWS Fargate while
  offering less. Fly, Render and Railway are cheaper. But their managed Postgres is the least battle-tested part of
  each product, and the database is what we least want to be adventurous about.

#### E3 — Self-hosted PaaS on Hetzner (Coolify / Dokku / CapRover), or Sliplane

This option answers one specific objection: "PaaS ergonomics are what I want, the bill is what I object to". Run
[Coolify](https://coolify.io/), Dokku or CapRover on a Hetzner VM, and get push-to-deploy, TLS and a web dashboard at
Hetzner prices. Or pay Sliplane €9–24 a month to run that layer for you on the same German hardware.

- **Pros**: Cost identical to Option A (~€25–35/month for prod + staging). Push-to-deploy without writing a Helm chart. German data centres either way.
- **Cons**: **It does not give you managed PostgreSQL.** Coolify will happily run a Postgres container with a backup
  cron. That is still our backups, our restores, our upgrades. So E3 buys deployment ergonomics, not the thing Option
  A's "Negative" section is actually worried about. Coolify itself is one more self-hosted control plane to patch.

### Option F — Managed Kubernetes at a hyperscaler (EKS / GKE / AKS)

Evaluated and **rejected on price alone**. EKS and GKE both charge roughly **$0.10 per cluster-hour, or $73/month for
the control plane before a single pod runs**. GKE waives one zonal cluster, and AKS's free tier has no uptime SLA. Add
nodes, a load balancer, NAT and a managed database, and the floor is $150–250/month per environment. That is the
correct answer for a team running many services. It is indefensible for two containers. This rejection is about
_managed K8s at hyperscaler prices_, not about Kubernetes. Option A uses Kubernetes, with a control plane we run
ourselves for ~€0.

### Option G — Hybrid: cheap EU compute + specialist managed Postgres

Hetzner (or a PaaS) for compute, paired with a specialist database. **Neon** costs ≈$0.106/CU-hour on Launch and has
EU regions. **Aiven** is a Finnish or EU company at ≈$60–80/month for a production-grade small plan. **Supabase Pro**
is ≈$25/month.

- **Pros**: Removes the single biggest drawback of Option A — we stop owning Postgres backups and PITR — while keeping cheap compute. Neon's branching is a
  genuinely nice fit for a staging stage.
- **Cons**: Two vendors, two DPAs, two bills, and cross-provider network latency on every query, which matters for a WebFlux/R2DBC app doing chatty per-event
  upserts. Aiven is EU-owned. Neon and Supabase are US companies with EU regions.

### Option H — AWS Elastic Beanstalk (`eu-central-1`)

AWS's own PaaS, and the cheapest way to run this application _on AWS_. One `eb deploy` provisions plain EC2 instances
(Amazon Linux 2023, Corretto or Docker platform), an optional load balancer, an auto-scaling group and CloudWatch
wiring. **Beanstalk itself is free** — you pay only for the resources underneath. It is actively maintained: platform
updates shipped roughly monthly through 2026, and the AL2-based branches retire on 30 June 2026 in favour of AL2023.

- **Pros**: Removes the two line items that make Option B expensive. A **single-instance environment** has _no load balancer_
  (~−$24/month) and sits in a public subnet, so it needs **no NAT Gateway** (~−$40/month). That deletes $64/month of
  Option B's floor, which is why Beanstalk lands at roughly half of Fargate. EC2 is cheaper per GB of RAM than
  Fargate, and Graviton (`t4g`) is cheaper again. RDS is still available as the
  managed Postgres, which is the best in this comparison. **Beanstalk's single-instance deploy model — terminate, then start — is exactly the `Recreate`
  semantics ADR-008 needs for the importer**, for free. Everything else about AWS (OIDC from GitHub Actions, `eu-central-1` residency, the ESC option,
  CloudWatch, the Terraform provider) still applies.
- **Cons**: it is a **PaaS veneer over EC2, not a container platform**. You own instance sizing and capacity.
  Beanstalk offers managed platform updates, but patching is something you configure rather than something that
  disappears. Still ~3× Hetzner and ~2× a self-hosted PaaS. The deploy model is dated: application versions in S3,
  `.ebextensions`, and one environment per service. Two services therefore mean two environments and two EC2
  instances, unless we co-locate them. **Do not let Beanstalk create the RDS instance.** An environment-owned database
  is destroyed with the environment, so provision the database separately and pass it in as configuration. AWS's own
  momentum is behind ECS/App Runner, so Beanstalk is stable rather than growing.

## Comparison

Scores are for **this** workload at **this** scale, not in general.

Columns:

- **A** Hetzner + k3s
- **B** AWS Fargate
- **H** AWS Beanstalk
- **C** GCP Cloud Run
- **E1** EU PaaS (Scalingo/Clever Cloud)
- **E2** US PaaS (Fly/Render/Heroku)

| Criterion (weight)               | A                    | B                  | H                  | C                 | E1                | E2             |
| -------------------------------- | -------------------- | ------------------ | ------------------ | ----------------- | ----------------- | -------------- |
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

One production stage:

- `events-bff` — 0.5 vCPU / 1 GB
- `events-importer` — 0.5 vCPU / 1 GB
- PostgreSQL — 2 vCPU / 4 GB, 40 GB storage
- the static SPA
- under 100 GB egress/month

Plus one staging stage at roughly half that. All figures **as of 2026-08-03** (PaaS, Beanstalk and App Engine tables:
**2026-08-05**), EU regions, list price, excl. VAT/credits.

### Option A — Hetzner Cloud (recommended)

| Item                                                 | Plan                         | € / month  |
| ---------------------------------------------------- | ---------------------------- | ---------- |
| k3s node — bff + importer + frontend + ingress       | CX33 (4 vCPU / 8 GB / 80 GB) | 8.49       |
| PostgreSQL VM (private network only, no public IPv4) | CX23 (2 vCPU / 4 GB / 40 GB) | 5.49       |
| Public IPv4 (k3s node only)                          | 1 ×                          | ~1.70      |
| Automated snapshots (20 % of server price)           | both servers                 | ~2.80      |
| Backup target for WAL + base backups                 | Storage Box BX11 (1 TB)      | ~3.81      |
| **Production subtotal**                              |                              | **~22.30** |
| Staging — everything on one node                     | CX23 + IPv4                  | ~7.20      |
| **Total (prod + staging)**                           |                              | **~29.50** |

Add a Hetzner Load Balancer (LB11, ~€7.49/month) only when a second k3s node arrives. Until then the ingress binds
the node IP directly.

### Option B — AWS `eu-central-1`

| Item                                                              | $ / month     |
| ----------------------------------------------------------------- | ------------- |
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

Note how the shape differs from Hetzner. **Load balancer and NAT Gateway are ~$64 of that**, two line items that do
no application work and cannot scale down.

### Option C — Google Cloud (Cloud Run)

Each always-on container is 0.5 vCPU / 1 GiB with instance-based (always-allocated CPU) billing, ≈ 730 h/month.

| Item                                                                        | Tier 1 (`europe-west1`, BE) | Tier 2 (`europe-west3`, DE) |
| --------------------------------------------------------------------------- | --------------------------- | --------------------------- |
| Cloud Run **worker pool** — importer, 1 instance, always on                 | ~29                         | ~40                         |
| Cloud Run **service** — BFF, `min-instances: 1` (JVM cold starts otherwise) | ~29                         | ~40                         |
| Cloud SQL — smallest shared-core + 40 GB SSD + public IPv4                  | ~45                         | ~45                         |
| Firebase Hosting / Cloud Storage + CDN for the SPA                          | ~1                          | ~1                          |
| Artifact Registry, Secret Manager, Cloud Logging                            | ~5                          | ~5                          |
| **Production subtotal ($)**                                                 | **~110**                    | **~130**                    |
| Staging (BFF scales to zero; smallest Cloud SQL)                            | ~30                         | ~35                         |
| **Total (prod + staging, $)**                                               | **~140**                    | **~165**                    |

The Frankfurt column is the price of the German _preference_. Belgium and the Netherlands are Tier 1, and satisfy the
EU _requirement_ for ~$25/month less.

### Option C2 — Google App Engine (for comparison only)

| Item                                                                  | $ / month |
| --------------------------------------------------------------------- | --------- |
| Flexible — importer, 1 vCPU / 1 GB, cannot scale below 1 instance     | ~48       |
| Flexible — BFF, 1 vCPU / 1 GB                                         | ~48       |
| Cloud SQL + registry + logging (as above)                             | ~50       |
| **Production subtotal**                                               | **~145**  |
| Standard-env alternative: 2 × F2 (512 MB) resident, **compute alone** | ~150      |
| Standard-env at a realistic 1 GB: 2 × F4 resident, **compute alone**  | ~290      |

### Option H — AWS Elastic Beanstalk `eu-central-1`

| Item                                                                           | $ / month    |
| ------------------------------------------------------------------------------ | ------------ |
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

Beanstalk is roughly **half of Fargate** for the same application. The saving is almost entirely the ALB and NAT
Gateway that a single-instance environment does not need. The trade is that we are back to sizing EC2 instances — a
PaaS in workflow, an IaaS in responsibility.

### Option E — PaaS

Two always-on 1 GB containers plus a managed Postgres, prod only, list price ex-VAT. PaaS providers publish
per-GB-RAM prices and size their databases differently. So these are **estimates from vendor calculators and public
price lists**. Re-check with each vendor's own estimator before you commit.

#### E1 — European providers

| Provider                 | Configuration                                                                           | Prod / month | + staging |
| ------------------------ | --------------------------------------------------------------------------------------- | ------------ | --------- |
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
| ------------------------- | --------------------------------------------------------------------------------------- | ------------ |
| Fly.io (`fra`)            | 2 × shared-cpu-1x / 1 GB + Managed Postgres + volumes                                   | ~$50         |
| DigitalOcean App (`fra1`) | 2 × Basic (1 GB) + Managed Postgres 1 GB; static site free                              | ~$45–60      |
| Railway (EU West)         | 2 svc × (0.5 vCPU + 1 GB) @ $20/vCPU + $10/GB + Postgres + $20 Pro seat                 | ~$65         |
| Render (Frankfurt)        | 2 × Standard (1 CPU / 2 GB) @ $25 + Postgres Basic 1 GB; static site free               | ~$70         |
| **Heroku** (EU/Ireland)   | 2 × Standard-2X (1 GB) @ $50 + Postgres Standard-0 $50                                  | **~$150**    |
| Heroku, minimal           | 2 × Standard-1X (512 MB) @ $25 + Postgres Essential-2 $20 (shared, 4 h/mo downtime SLA) | ~$70         |

Heroku at production sizing costs the same as AWS Fargate, for a fraction of the platform. It is the reference
implementation of this category, not the value option. Its EU region is Ireland (on AWS), so it is a US processor with
EU residency.

### Summary — what this application costs per month

| Platform                                 | Type      | Managed PG | EU jurisdiction | Production | Prod + staging | Multiple of cheapest |
| ---------------------------------------- | --------- | ---------- | --------------- | ---------- | -------------- | -------------------- |
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

Three honest caveats on this table.

First, hyperscaler **free credits** distort year one. AWS and GCP both hand new accounts a few hundred dollars. That
can make the first 6–12 months look free and the thirteenth month look alarming. This table is steady-state.

Second, the Hetzner number **excludes the labour** of running PostgreSQL and k3s ourselves. Value that at even two
hours a month and the gap to a PaaS narrows considerably. That is precisely the trade-off the decision below turns on.

Third, the PaaS rows are the _published_ prices for a sizing nobody has load-tested. Every one of them bills per GB of
RAM. If the JVMs need 2 GB rather than 1 GB, the PaaS rows roughly double and the Hetzner row does not move at all.

**Is PaaS cheaper than CaaS?** Yes, meaningfully. A European PaaS with managed Postgres lands at **€60–120/month**,
against **$110–150** for Cloud Run or Fargate. And it deletes the ops burden, not merely the YAML. But it is still
**3–5× Hetzner**. The cheapest way to get PaaS _ergonomics_ is not to buy a PaaS at all: Sliplane or Coolify on German
hardware costs ~€25/month. That buys push-to-deploy, and explicitly does _not_ buy managed PostgreSQL.

---

## Decision

**Option A — Hetzner Cloud (Nuremberg/Falkenstein), k3s + Helm, provisioned with OpenTofu, PostgreSQL on a dedicated VM with `wal-g` PITR backups.**

Rationale, against the weighted criteria:

- **The Germany requirement is decisive and Hetzner satisfies it best.** It is a German company, in German data
  centres, under German jurisdiction, with a standard AVV. No SCCs, no CLOUD Act analysis, no sovereign-cloud premium.
  AWS's European Sovereign Cloud (GA since
  January 2026, Brandenburg) is the only candidate that matches this posture. It costs more than regular AWS and
  offers fewer services.
- **Cost is not a rounding error at this scale — it is ~6× between the top and bottom of the table.** For a
  pre-revenue project, €30/month versus $200/month decides something real. It is the difference between "run staging
  and production properly" and "cut corners to keep the bill down". The saving directly funds the required staging
  stage, the domain, and an external uptime/monitoring service.
- **The workload wants exactly what a VM is.** `events-importer` is an always-on, single-instance, long-job scheduler (ADR-008). Every serverless pricing model
  in this comparison is optimised for the opposite shape, so we would pay a premium for elasticity the application cannot use.
- **The existing skills apply in full, and the Helm chart is on the backlog anyway.** Options B, C and E all discard
  the Kubernetes/Helm layer. Only Option A turns the planned "exercise the Helm chart on k3d/kind" work
  (TODO → Operations & Hardening) into the actual production deployment path.
- **Egress is included (20 TB/server).** For a public, image-heavy events site, metered egress is the most likely source of a surprise bill on any other option.
- **The PaaS layer was re-examined on its merits and does not beat this today.** European PaaS is genuinely cheaper
  than the container platforms. Clever Cloud at ~€60–90/month is well under Cloud Run or Fargate. Scalingo is the only
  non-Hetzner candidate that pairs EU jurisdiction with a managed Postgres that does PITR. But it is still 3–5×
  Hetzner, it discards the Helm work, and its Terraform story is thin to absent. That conflicts with the very next
  backlog item. The honest summary: **PaaS is what we buy when ops time becomes the binding constraint, not when euros
  are.** The fallback ranking below carries that trade, so a future switch needs no re-litigation of this ADR.

**The cost of this decision is that we own PostgreSQL and the k3s control plane.** That is the real trade, and this ADR
does not gloss over it. RDS and Cloud SQL give automated backups, tested restores, PITR and minor-version upgrades for
free. Here we buy all of that with our own time. The mitigations below are therefore **not optional** — they are the
price of the decision.

### Deployment shape

> **This sketch is what the decision was made against, not what runs.** It predates WireGuard admin access, Flux, OpenObserve and the object-storage backups.
> **[PLATFORM_SETUP.md](../ops/PLATFORM_SETUP.md) §1 is the current picture**, in rendered diagrams.

```
           DNS only — registrar or Hetzner DNS. No proxy, no edge, no US processor.
                                        │
                            ┌───────────┴────────────┐
                            │   Hetzner CX33 (k3s)   │   Falkenstein / Nuremberg
                            │  ┌──────────────────┐  │
                            │  │ Traefik ingress  │  │   terminates TLS (cert-manager / ACME)
                            │  │   /      → web   │  │   nginx serving Vite dist/
                            │  │   /api   → bff   │  │   events-bff       (N replicas)
                            │  │  (admin: private)│  │   events-importer  (exactly 1)
                            │  │  rate-limit mw   │  │   + PerHostThrottlingFilter in the BFF
                            │  └──────────────────┘  │
                            └───────────┬────────────┘
                                        │ private network (no public IP)
                            ┌───────────┴────────────┐
                            │   Hetzner CX23         │   PostgreSQL 18
                            │   + wal-g → Storage Box│   base backups + WAL, PITR
                            └────────────────────────┘
```

### Frontend hosting — containerise it, same origin as the API

`events-frontend` is a plain Vite SPA: `npm run build` produces a static `dist/`, and there is no SSR, no Nuxt, no
server runtime. That means the _industry default_ would be a static host plus a CDN: Cloudflare Pages, Netlify, Vercel,
S3+CloudFront. For a generic SPA that is the right answer, and it is usually free.

**For this project we should still ship it as a Docker image.** The build is multi-stage: `node` builds, and `nginx` or
`Caddy` serves `dist/`. The same Helm chart deploys it behind the same ingress. The reasons are specific rather than
dogmatic:

1. **Same origin removes CORS entirely.** One ingress routes `/` to the frontend and `/api` to the BFF. There are no
   preflight requests, and no `Access-Control-*` configuration to keep in sync across three environments. Session
   cookies are also first-party, so `SameSite` and third-party-cookie restrictions stop being a problem. That matters
   for the planned authentication work (TODO 🟠 Next). This alone justifies the choice.
2. **It keeps the Germany requirement intact.** Cloudflare Pages, Netlify and Vercel are US companies that serve from a
   global edge. Each one adds a processor and a jurisdiction question that a container in Falkenstein does not.
3. **One pipeline, one rollback.** Same registry, same `helm upgrade`, same `helm rollback`, same environment promotion. A second deploy mechanism for one
   static bundle is not worth the split.
4. **The cost is negligible.** nginx serving a few MB of assets runs comfortably in 16–32 MB of RAM.

Three things to get right, because a containerised SPA is easy to ship subtly broken:

- **History-mode fallback.** vue-router uses HTML5 history mode, so nginx needs `try_files $uri $uri/ /index.html;` — without it, deep links and page refreshes
  return 404.
- **Cache headers.** Vite content-hashes everything under `/assets/`, so serve those with `Cache-Control: public, max-age=31536000, immutable` and serve
  `index.html` with `no-cache`. Get this backwards and users either never see deploys or re-download the bundle constantly.
- **Build-time config.** Vite inlines `import.meta.env.*` at build time, so a per-environment API URL would mean one
  image per environment. The SPA calls a **relative** `/api` path instead. The image is then environment-agnostic, and
  the identical artifact promotes from staging to production.

**On a PaaS, invert this.** The reasoning above holds because a container on Hetzner costs cents. On a per-GB-RAM PaaS,
an nginx serving 3 MB of assets bills like any other container: €28.80/month on Scalingo, $25 on Render. A CDN does
that work for free.

So under any Option E fallback, host the SPA as a static site instead. Render, DigitalOcean and Northflank include
static sites at no charge. Scalingo and Clever Cloud do not, so use a Hetzner Object Storage bucket. The CORS cost
comes back with it: an explicit `Access-Control-Allow-Origin` allowlist on the BFF per environment, plus
`SameSite=None; Secure` cookies once authentication lands. That is a real but bounded amount of configuration, and the
correct trade at PaaS prices — roughly €30/month against an hour of CORS setup.

**Nothing sits in front** (amended 2026-08-10, [#412](https://github.com/enorm-labs/event-junkie/issues/412)). The
registrar or Hetzner DNS serves the DNS, and it resolves straight to the k3s node. **Traefik terminates TLS** in the
cluster, through cert-manager or Traefik's own ACME client. A German controller and a German processor handle every
byte of every request. That is the strongest reading of criterion 5, and the reason for the option.

The cost is that the edge was doing three jobs and now nobody is:

- **Rate limiting and DDoS** — a Traefik rate-limit middleware plus the BFF's existing `PerHostThrottlingFilter`, over Hetzner's volumetric protection. This is
  now work rather than a free by-product, and it belongs to [#268](https://github.com/enorm-labs/event-junkie/issues/268).
- **TLS certificates** — cert-manager, and the Helm chart provisions it. `deploy/charts/event-junkie` sets the
  `cert-manager.io/cluster-issuer` annotation on the Ingress. It also ships an optional `ClusterIssuer`, off by
  default, because that is a cluster-scoped singleton. It deliberately does **not** install
  cert-manager itself or own its CRDs — that is [#265](https://github.com/enorm-labs/event-junkie/issues/265). Traefik's own ACME client was not taken, for the
  reasons in [docs/ops/PLATFORM_SETUP.md](../ops/PLATFORM_SETUP.md) §6.
- **CDN caching of the SPA bundle** — not replaced, and not worth replacing. One nginx serving a few content-hashed megabytes to Berlin-scale traffic, with 20 TB
  of egress included, does not need a CDN. Revisit if the audience stops being local.

### When to revisit

This decision should be **reopened**, not defended, if any of these become true:

- The project takes on **a team, paying customers, or a compliance obligation** — SOC 2, or an enterprise customer's
  security review. Managed services and audit trails then start to earn their price.
- **Database operations become a recurring source of pain or fear** — the first restore that does not work is the signal. That is fallback 1 below: keep Hetzner
  compute, move Postgres to a managed EU provider. The next step up is fallback 3 — Clever Cloud or Scalingo, and hand over the compute as well.
- **Evenings spent on the platform exceed evenings spent on the product for two months running.** That is the
  concrete trigger for the PaaS fallback. A fair price for that time is €40–90 a month. Make the decision on that
  evidence, not pre-emptively either way.
- **Uptime requirements harden** past what a single-region, single-node k3s cluster can honestly promise.
- The roadmap pulls in **managed building blocks**. README lists Elasticsearch as "maybe later", and TODO lists
  Keycloak and auth. Once we want managed search, managed identity and managed queues, the hyperscaler discount on
  _integration effort_ starts to outweigh the compute premium.

**The ranked fallbacks**, in the order to reach for them. This list is not dead prose. The decision was accepted with
one serious risk: Postgres operations. Fallback 1 gives that risk a cheap answer, and needs no reopening of this ADR.

1. **Keep Hetzner, move PostgreSQL to a managed EU provider** (Option G). Try this first if the Postgres-ops risk
   materialises. It is the only fallback that discards nothing. The Helm chart, the OpenTofu configuration, the
   containerised SPA and the same-origin `/api` arrangement all survive. The application changes by one connection
   string. **Aiven** is EU-owned (Finnish) and keeps the jurisdiction property intact at ~€60–80/month. Neon and
   Supabase are cheaper, but they are US companies with EU regions, so they trade jurisdiction for price. The cost is
   a second vendor, a second DPA, and cross-provider latency on every query. That matters more here than usual,
   because R2DBC upserts are chatty. Benchmark before you commit.
2. **Sliplane, or Coolify/Dokku self-hosted on Hetzner** (Option E3). Take this if the objection is "I don't want to
   write a Helm chart". It is not the answer to "I don't want to run a database". Same ~€25–35/month, push-to-deploy,
   German hardware, and it stays on the Hetzner escape path. It does **not** solve Postgres ops, so it composes with
   fallback 1 rather than replacing it.
3. **Clever Cloud, or Scalingo** (Option E1) — if the objection is ops time in general, compute as well as database.
   French SAS, EU jurisdiction and EU data centres. Managed PostgreSQL with automated backups and PITR, and native
   Spring Boot support. Production costs ~€60–120/month. Scalingo is the safer, more Heroku-like product, with a
   SecNumCloud-qualified region. Clever Cloud is roughly a third cheaper. This fallback **inverts the frontend-hosting
   decision**: the SPA moves to a static host and the CORS cost comes back. See §Frontend hosting.
4. **GCP Cloud Run + Cloud SQL** (Option C) — the best hyperscaler fit for this shape. No load-balancer line item, free
   scale-to-zero staging, and worker pools that match ADR-008's always-on single-instance scheduler natively. Deploy
   to `europe-west1` or `europe-west4` (Tier 1), unless German soil is worth ~$25/month.
5. **AWS Elastic Beanstalk** (Option H) — if AWS is wanted for career or ecosystem reasons, but Fargate's floor is not.
   Roughly half of Fargate at ~$80–100/month, because a single-instance environment needs neither an ALB nor a NAT
   Gateway. RDS is also the best managed Postgres in this document.
6. **AWS Fargate** (Option B) is last for _this_ stage of the project. It becomes first the moment breadth of managed
   services or enterprise credibility binds.

Two options are **not** recommended at any position. **Heroku**: production sizing costs as much as Fargate for a
fraction of the platform, and its EU region is Ireland on AWS. **App Engine**: more expensive than Cloud Run,
region-locked per project, and Google's own direction of travel is away from it.

### On "AWS is the most flexible and the standard, isn't it?"

Both halves are true, and neither is decisive here.

**"The standard"** — yes, by market share (~28–31 %) and by enterprise default. That is a strong reason to _know_ AWS and a weak reason to _host on it_. Nothing
in this application needs a service that only AWS has.

**"The most flexible"** — yes, in breadth of catalogue. But flexibility at AWS is sold as _provisioned capacity with
an hourly floor_. An ALB, a NAT Gateway and an RDS instance bill ~$96/month combined before the application does
anything. That is the price of options we would not exercise. Flexibility we pay for monthly and never use is not
flexibility, it is overhead.

There is also a quieter point. For a two-service application, ECS+Fargate is the _least_ ergonomic of the
managed-container platforms compared here. Cloud Run and Container Apps give an HTTPS endpoint from an image. ECS wants
task definitions, target groups, listener rules, execution roles and log groups, and we would author and maintain all
of it in Terraform. AWS's flexibility is real, but at this size it is charged in both euros and YAML.

The honest summary: **AWS is the right answer to a different question.** That question has a team, a compliance
requirement, or a service catalogue to draw on. Choosing Hetzner now does not close that door. The application is
containers and
Postgres, and the Helm chart is the portable artifact.

### The Cloudflare amendment

As first written, this ADR put Cloudflare's free plan in front for DNS, TLS, CDN and rate limiting. It flagged one
nuance: strictly German-only processing would mean _"either dropping Cloudflare's proxy mode or buying its EU
data-localisation add-on"_. **The second option does not exist at this tier.** Cloudflare's Data Localization Suite is
an Enterprise-only add-on, custom-priced through direct sales. That left a straight either/or, and the strict reading
of criterion 5 won: **no US processor in the request path at all.**

**What it costs.** Three things Cloudflare was doing for free needed answers, and two of them are not free:

- **Edge DDoS and rate limiting** — gone. What remains is the existing `PerHostThrottlingFilter`, a Traefik rate-limit middleware, and Hetzner's volumetric
  protection. This _removes_ progress on [#268](https://github.com/enorm-labs/event-junkie/issues/268) rather than making it.
- **Edge access control for the admin UI** — Cloudflare Access was named as "the cheapest fit". WireGuard replaced it,
  and more cleanly. The admin surface is unreachable rather than authenticated (PLATFORM_SETUP §8.1).
- **CDN caching of the SPA assets** — no longer applies. One nginx, a few MB, and Hetzner includes 20 TB of egress.

**What it buys.** One fewer processor, one fewer DPA, no SCCs and no transfer mechanism to name — a single AVV with
Hetzner. It also settled an open question in [ADR-014](ADR-014_RENDERING_STRATEGY.md). The head-rewriting transport was
undecided between a Cloudflare Worker and an in-cluster sidecar. It is the sidecar.

**One thing it makes harder.** Behind Cloudflare the origin saw a proxy IP. Without it, **Traefik and nginx see real client IPs**, so log truncation and
retention become more load-bearing, not less — [LEGAL.md](../LEGAL.md) §7.5.

---

## Consequences

- **Positive**: Lowest total cost by a wide margin (~€30/month for production _and_ staging), so the required staging stage is affordable. Strongest GDPR/data
  residency position of any candidate. Docker, Kubernetes, Helm, and Terraform skills apply directly, and the planned Helm chart becomes the production
  deployment path. Egress is included, removing the most common bill-shock vector. Always-on containers suit ADR-008's scheduler without workarounds. Low
  lock-in — the workload is containers, Kubernetes manifests, and Postgres.
- **Negative**: **We own PostgreSQL.** Backups, PITR, restore verification, and minor-version upgrades are ours. We also own the k3s control plane, OS patching,
  and node upgrades. No managed observability. Single region and, initially, a single node — a node failure is an outage. Email-only support.
- **Backups are the load-bearing mitigation** (`wal-g` or `pgBackRest` streaming to Hetzner Storage Box, plus Hetzner server snapshots). A **restore drill must
  be part of the go-live checklist and repeated on a schedule** — an untested backup is not a backup. This is the
  single highest-risk item this ADR creates. A failed drill triggers fallback 1 (managed EU Postgres), not a round of
  heroics.
- **Single-instance importer**: the Helm chart must set `replicas: 1` with `strategy: Recreate` for `events-importer` so a rolling deploy never runs two
  schedulers. Multi-replica operation stays blocked on the `SELECT … FOR UPDATE SKIP LOCKED` work noted in ADR-008.
- **Admin API exposure**: the ingress must not route `events-importer`'s admin endpoints publicly. They stay a
  cluster-internal service, reachable through `kubectl port-forward` or, later, behind the planned authentication.
  **The planned admin frontend inherits this.** At launch it runs locally against a port-forwarded admin API, and is
  not deployed at all. Once it _is_ deployed, it needs access control ahead of the application-level auth work: an
  ingress IP allowlist, or a Traefik basic-auth middleware. Do not route either the admin UI or the admin API publicly
  on the assumption that "nobody knows the URL".
- **IaC**: use the `hetznercloud/hcloud` OpenTofu/Terraform provider for servers, networks, firewalls and volumes.
  State lives in **Hetzner Object Storage**, not Terraform Cloud — one vendor, one jurisdiction, one AVV. The
  configuration exists in [`infra/`](../../infra), unapplied. Two things the decision did not anticipate. You have to
  create the state _bucket_ by hand. A backend cannot be managed by the state it holds, and Hetzner has no Cloud API
  for buckets. And S3-native locking is unverified on Hetzner's Ceph, so applies are single-operator until someone
  tests it.
- **CI/CD**: GitHub Actions cannot use OIDC against Hetzner. A deploy authenticates with a scoped kubeconfig or deploy
  key, held as a repository secret and rotated deliberately. This is a genuine step down from AWS/GCP OIDC, and
  deserves to be treated as one.
- **Observability is our problem now.** Budget for a self-hosted `kube-prometheus-stack` + Grafana, which fits the
  "Dashboard for analysing the data" backlog item. An external SaaS free tier is the alternative. Alerting must exist
  before launch, not after the first outage.
- **Frontend**: adds a `Dockerfile` and an nginx config to `events-frontend/`. The SPA must call the API through a
  relative `/api` path, so one image serves every environment.
- **DNS and TLS are ours** (2026-08-10 amendment). DNS sits at the registrar or Hetzner DNS. Traefik terminates TLS
  in the cluster, via cert-manager or its own ACME client. The Helm chart must provision the issuer and the
  certificate, and a
  certificate that fails to renew is an outage nobody else notices first. Hetzner DNS is in the **official**
  `hetznercloud/hcloud` provider — `hcloud_zone` and `hcloud_zone_rrset`, GA since v1.56.0. It therefore uses the same
  provider, token and state file as the servers. Do not use a community provider. `timohirt/hetznerdns` and
  `germanbrew/hetznerdns` were both deprecated on 10 Nov 2025, and they still rank well in search results. That is
  exactly how a deprecated provider ends up in a fresh configuration.
- **Real client IPs reach the origin** (2026-08-10 amendment). With no proxy in front, Traefik and nginx see the actual address rather than an edge IP. That
  makes the four open logging decisions in [LEGAL.md](../LEGAL.md) §7.5 load-bearing rather than a formality. They
  are: whether to log IPs at all, truncation, retention, and where retention is enforced. Settle them before launch,
  not after.
- **Cost re-check**: Hetzner raised prices in 2026 and may again. Re-verify the numbers in this ADR at go-live and revisit annually.
- **Both exits are pre-decided, not pre-committed**, and they are different sizes. If _the database_ is the problem,
  fallback 1 moves Postgres to a managed EU provider and nothing else changes. If _ops time in general_ is the
  problem, the move is Clever Cloud or Scalingo — EU jurisdiction, managed Postgres with PITR. The SPA then moves to a
  static host rather than staying a container. See the fallback ranking. What keeps both exits cheap is an application
  that is "a Docker image plus a Postgres URL", with no Kubernetes-specific code. The Helm chart is the only artifact
  thrown away, and only by the second exit.
- **Follow-ups unblocked** — the rest of the `v0.2 — Deployable` milestone, no longer blocked on this decision. The
  go-live checklist (legal, security, SEO, monitoring, alerting, dashboards, backups, recovery) follows in `v1.0`.
    - [#259](https://github.com/enorm-labs/event-junkie/issues/259) register `event-junkie.de`
    - [#260](https://github.com/enorm-labs/event-junkie/issues/260) the OpenTofu configuration
    - [#261](https://github.com/enorm-labs/event-junkie/issues/261) the Helm chart
    - [#262](https://github.com/enorm-labs/event-junkie/issues/262) containerise the frontend
    - [#263](https://github.com/enorm-labs/event-junkie/issues/263) exercise both on k3d
    - [#264](https://github.com/enorm-labs/event-junkie/issues/264) the release and deploy workflows
    - [#265](https://github.com/enorm-labs/event-junkie/issues/265) the staging stage

## References

- [Hetzner Cloud price adjustment, 15 June 2026](https://docs.hetzner.com/general/infrastructure-and-availability/price-adjustment/) — current CX/CPX/CAX/CCX
  pricing
- [Hetzner Cloud](https://www.hetzner.com/cloud/) · [
  `hetznercloud/hcloud` Terraform provider](https://registry.terraform.io/providers/hetznercloud/hcloud/latest/docs)
- [AWS Fargate pricing](https://aws.amazon.com/fargate/pricing/) · [Amazon VPC pricing (NAT Gateway, IPv4)](https://aws.amazon.com/vpc/pricing/)
- [AWS launches the European Sovereign Cloud, 15 January 2026](https://press.aboutamazon.com/aws/2026/1/aws-launches-aws-european-sovereign-cloud-and-announces-expansion-across-europe)
- [Google Cloud Run pricing](https://cloud.google.com/run/pricing) · [Cloud SQL pricing](https://cloud.google.com/sql/pricing)
- [AWS Elastic Beanstalk pricing](https://aws.amazon.com/elasticbeanstalk/pricing/) — the service is free, and you
  pay for EC2/RDS/ELB
- [Elastic Beanstalk platform release schedule](https://docs.aws.amazon.com/elasticbeanstalk/latest/dg/platforms-schedule.html)
  — AL2 branches retire 30 June 2026
- [App Engine pricing](https://cloud.google.com/appengine/pricing)
- [Deploy worker pools to Cloud Run](https://cloud.google.com/run/docs/deploy-worker-pools) — the always-on,
  manually-scaled primitive that fits ADR-008
- [Migrate from App Engine to Cloud Run](https://cloud.google.com/run/docs/migrate/from-app-engine-to-cloud-run)
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
- [The `v0.2 — Deployable` milestone](https://github.com/enorm-labs/event-junkie/milestones) — the path to go-live
