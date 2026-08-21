# Links

Every external service, console and reference this project depends on, in one place. The companion files are
[`event-junkie-bookmarks.html`](event-junkie-bookmarks.html) — the same set, importable into a browser — and [`CREDENTIALS.md`](CREDENTIALS.md), which lists
what you need to hold in a password manager to actually use any of it.

**Read the status column.** Most of this project is documented well ahead of being deployed: staging exists, production does not, and several accounts below are
decided rather than opened. A link that resolves is not evidence that the thing behind it is running.

Everything here is derived from the repository's own documentation. Where a document names a service but not its entry point — INWX, the Hetzner status page —
the obvious console URL is filled in and marked _(added)_.

---

## 1. The project on GitHub

| Link                                                                                  | What it is                                                                       |
| ------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| <https://github.com/enorm-labs/event-junkie>                                          | The repository. Public, Apache-2.0                                               |
| <https://github.com/enorm-labs>                                                       | The organisation — deploy keys are enabled here, not per repository              |
| <https://github.com/enorm-labs/event-junkie/issues>                                   | The backlog                                                                      |
| <https://github.com/enorm-labs/event-junkie/milestones>                               | `v0.2 — Deployable`, `v0.3 — Launch-ready`, `v1.0 — Go-live`                     |
| <https://github.com/enorm-labs/event-junkie/discussions>                              | Questions and product ideas                                                      |
| <https://github.com/enorm-labs/event-junkie/actions>                                  | CI — 14 workflows                                                                |
| <https://github.com/enorm-labs/event-junkie/security>                                 | The Security tab, worked by `/security-triage`                                   |
| <https://github.com/enorm-labs/event-junkie/security/dependabot>                      | Dependabot alerts                                                                |
| <https://github.com/enorm-labs/event-junkie/security/code-scanning>                   | Code scanning alerts                                                             |
| <https://github.com/orgs/enorm-labs/packages>                                         | GHCR packages — **each is private on first publish and needs one click to flip** |
| <https://github.com/enorm-labs/event-junkie/issues/new?template=wrong-event-data.yml> | Public form: wrong or missing event data                                         |
| <https://github.com/enorm-labs/event-junkie/issues/new?template=new-venue.yml>        | Public form: suggest a venue                                                     |
| <https://github.com/enorm-labs/event-junkie/issues/new?template=bug.yml>              | Public form: bug in the site or API                                              |

### The published artifacts

Not browsable URLs — these are the OCI references CI pushes and Flux pulls. Four packages, one version, published by `release.yml`.

```
ghcr.io/enorm-labs/event-junkie/bff:<version>
ghcr.io/enorm-labs/event-junkie/importer:<version>
ghcr.io/enorm-labs/event-junkie/frontend:<version>
oci://ghcr.io/enorm-labs/charts/event-junkie:<version>
```

---

## 2. Hetzner — the one infrastructure provider

ADR-012, as amended on 2026-08-10, leaves exactly one processor. Everything below is the same account.

| Link                                                                                               | What it is                                                                       | Status                           |
| -------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- | -------------------------------- |
| <https://console.hetzner.com>                                                                      | Cloud Console — projects, servers, volumes, firewalls, DNS zones, Object Storage | In use                           |
| <https://konsoleh.hetzner.com>                                                                     | **konsoleH** — Webhosting S, where the role mailboxes live. A separate login     | In use since 2026-08-21          |
| <https://webmail.your-server.de>                                                                   | Webmail for `hello@` and `security@`. IMAP/SMTP: `mail.your-server.de`, 993/465  | In use since 2026-08-21          |
| <https://accounts.hetzner.com/account/dpa>                                                         | The **AVV** (Art. 28 DPA), self-service                                          | Concluded 2026-08-19             |
| <https://fsn1.your-objectstorage.com>                                                              | S3 endpoint, Falkenstein. Buckets `event-junkie-tfstate`, `-o2`, `-backups`      | `-tfstate` and `-backups` in use |
| <https://api.hetzner.cloud/v1>                                                                     | Cloud API — token-authenticated, not browsable                                   | In use                           |
| <https://docs.hetzner.com>                                                                         | Product documentation                                                            | Reference                        |
| <https://community.hetzner.com>                                                                    | Tutorials — the k3s and hardening guides came from here                          | Reference                        |
| <https://status.hetzner.com>                                                                       | Status page _(added)_                                                            | Reference                        |
| <https://registry.terraform.io/providers/hetznercloud/hcloud/latest/docs/guides/s3-object-storage> | The S3 guide the OpenTofu backend is built against                               | Reference                        |
| <https://registry.terraform.io/providers/hetznercloud/hcloud/latest/docs>                          | `hcloud` provider reference                                                      | Reference                        |

**The buckets and what they hold** — one subscription, €4.99/month, 1 TB included, buckets free:

| Bucket                 | Holds                                                          | Versioning                        |
| ---------------------- | -------------------------------------------------------------- | --------------------------------- |
| `event-junkie-tfstate` | OpenTofu state. **Hand-made** — a backend cannot manage itself | On, 90-day noncurrent expiry      |
| `event-junkie-o2`      | OpenObserve's Parquet data (ADR-015)                           | Off                               |
| `event-junkie-backups` | `wal-g` WAL and base backups, 30-day window                    | **Off — deliberately.** See below |

> Versioning on `-backups` would keep a copy of everything `wal-g` deletes, so the retention window the privacy notice states would become decorative.

---

## 3. Domains, DNS and the sites

| Link                                                     | What it is                                                                                       | Status                                    |
| -------------------------------------------------------- | ------------------------------------------------------------------------------------------------ | ----------------------------------------- |
| <https://www.inwx.de> _(added)_                          | **Registrar.** Where the nameservers point at Hetzner and where the DNSSEC DS record lives       | Registered 2026-08-10; DNSSEC still to do |
| <https://event-junkie.de>                                | Production site                                                                                  | **Not deployed**                          |
| <https://staging.event-junkie.de>                        | Staging — **no public `A` record, reachable only over WireGuard**                                | Live                                      |
| <https://event-junkie.com>                               | Defensive registration, same records                                                             | Zone declared                             |
| <https://acme-v02.api.letsencrypt.org/directory>         | Let's Encrypt **production** ACME directory                                                      | Production only                           |
| <https://acme-staging-v02.api.letsencrypt.org/directory> | Let's Encrypt **staging** ACME directory — the default in every values file, including staging's | In use                                    |

> The production ACME rate limit is **per registered domain**, and `event-junkie.de` is the same registered domain in both environments. Burning it from
> staging would lock production out for a week.

Both domains were registered at **INWX on 2026-08-10** and delegate to `hydrogen`/`oxygen`/`helium.ns.hetzner.com`, closing
[#259](https://github.com/enorm-labs/event-junkie/issues/259) on 2026-08-12 — so the nameserver flip (PLATFORM_SETUP §10 Phase B step 9) is done.

**DNSSEC is not**, and it has no issue of its own: PLATFORM_SETUP §10 step 9 calls it _"a separate, later step — #259"_, but #259 is the registration issue and
is closed. A DS record at INWX that does not match Hetzner's key makes the domain _unresolvable_ rather than merely wrong, which is why it is a deliberate
sitting rather than a follow-on.

Role mailboxes `hello@event-junkie.de` and `security@event-junkie.de` are **live since 2026-08-21**, on Hetzner Webhosting S, and proven in both directions:
mail arrives, and replies authenticate `spf=pass` / `dkim=pass` / `dmarc=pass` against a `p=reject` policy. Every published reporting route now reaches
somebody. How they were built, what they cost and which DNS records carry them: [`ops/EMAIL.md`](ops/EMAIL.md).

---

## 4. Monitoring and alerting

Two layers, and the second is not optional: an alerting path that runs on the node it monitors cannot tell you the node is dead.

| Link                                               | What it is                                                                                   | Status                                                                            |
| -------------------------------------------------- | -------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| <https://healthchecks.io>                          | **The dead-man's switch**, deliberately off Hetzner. One account, one project, one channel   | Live — `walg-staging`, `walg-production`                                          |
| `https://hc-ping.com/<uuid>`                       | The ping endpoint. **Every ping URL is a credential** — see CREDENTIALS.md                   | Live                                                                              |
| <https://github.com/openobserve/openobserve>       | OpenObserve — logs, metrics, dashboards, alerting. AGPL-3.0, in-cluster, Parquet to `-o2`    | **Deployed on staging.** Operating it: [`ops/OPENOBSERVE.md`](ops/OPENOBSERVE.md) |
| <https://github.com/bbernhard/signal-cli-rest-api> | The Signal alert bridge — OpenObserve webhook → signal-cli. Needs its own prepaid number     | **Deployed, unregistered** — no number yet (#271)                                 |
| <https://www.netdata.cloud>                        | Netdata, **self-hosted only** — connecting it to Netdata Cloud would reintroduce a processor | Optional complement                                                               |

---

## 5. Delivery, GitOps and the cluster

| Link                                                                                             | What it is                                                                        |
| ------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------- |
| <https://fluxcd.io>                                                                              | Flux — pull-based delivery. CI holds no cluster credential (ADR-016)              |
| <https://cert-manager.io/docs/>                                                                  | cert-manager — Let's Encrypt, HTTP-01 in production, DNS-01 in staging            |
| <https://docs.k3s.io>                                                                            | k3s — the Kubernetes distribution, Traefik bundled                                |
| <https://get.k3s.io>                                                                             | The k3s install script, invoked from cloud-init                                   |
| <https://helm.sh/docs/>                                                                          | Helm — the chart lives in `deploy/charts/event-junkie`                            |
| <https://k9scli.io>                                                                              | k9s — for anything more than one `kubectl` command                                |
| <https://charts.jetstack.io>                                                                     | cert-manager's Helm repository                                                    |
| <https://charts.hetzner.cloud>                                                                   | Hetzner's Helm repository                                                         |
| <https://wal-g.readthedocs.io>                                                                   | `wal-g` — WAL archiving and base backups to S3                                    |
| <https://registry.opentofu.org>                                                                  | OpenTofu provider registry                                                        |
| <https://direnv.net/>                                                                            | direnv — loads `infra/.envrc` on entry and **unloads it on leaving**              |
| <https://charts.openobserve.ai>                                                                  | OpenObserve's Helm repository — `openobserve-standalone`, `openobserve-collector` |
| <https://opentelemetry.io/docs/collector/>                                                       | The OTel collector. **Everything is filtered here, not at OpenObserve**           |
| <https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/main/pkg/ottl/README.md> | OTTL — the language the drop rules are written in                                 |
| <https://github.com/mittwald/cert-manager-webhook-hetzner>                                       | The DNS-01 solver staging needs, since it has no public address                   |

---

## 6. Legal and business

| Link                                        | What it is                                                           | Status                                                |
| ------------------------------------------- | -------------------------------------------------------------------- | ----------------------------------------------------- |
| <https://accounts.hetzner.com/account/dpa>  | The Hetzner **AVV** — the project's only Art. 28 contract            | Concluded 2026-08-19. **File the countersigned copy** |
| <https://www.postflex.de/>                  | Rented _ladungsfähige Anschrift_ for the § 5 DDG imprint             | **Rented 2026-08-21** — €39.90/yr, in the imprint     |
| <https://datenschutz-generator.de/>         | Second-opinion cross-check on the German privacy notice (§7.8)       | Not done                                              |
| <https://www.contributor-covenant.org>      | Contributor Covenant 3.0 — the Code of Conduct's source              | Adopted                                               |
| <https://github.com/oss-review-toolkit/ort> | ORT — evaluated and **rejected** as disproportionate (LEGAL.md §9.1) | Not used                                              |

The site's own legal pages, once deployed, are `/legal/privacy`, `/legal/imprint` and `/legal/notices` under whichever origin is serving.

---

## 7. Security and dependency scanning

| Link                                                 | What it is                                                                      |
| ---------------------------------------------------- | ------------------------------------------------------------------------------- |
| <https://nvd.nist.gov/>                              | The National Vulnerability Database — what OWASP Dependency-Check scans against |
| <https://nvd.nist.gov/developers/request-an-api-key> | Where `NVD_API_KEY` comes from                                                  |
| <https://github.com/aquasecurity/trivy>              | Trivy — gates `release.yml` on fixable CRITICAL/HIGH before anything is pushed  |
| <https://github.com/gitleaks/gitleaks>               | gitleaks — pre-commit secret scanning, configured in `.gitleaks.toml`           |
| <https://github.com/zizmorcore/zizmor>               | zizmor — GitHub Actions static analysis, configured in `zizmor.yml`             |

---

## 8. Local development

Nothing here is a service you have an account with; it is the set of addresses `scripts/dev-env.sh up` produces.

| Address                                               | What                                         |
| ----------------------------------------------------- | -------------------------------------------- |
| <http://localhost:5173>                               | Frontend (Vite dev server)                   |
| <http://localhost:8080/webjars/swagger-ui/index.html> | `events-bff` Swagger UI                      |
| <http://localhost:8081/webjars/swagger-ui/index.html> | `events-importer` Swagger UI                 |
| `localhost:56298`                                     | PostgreSQL, started by Spring Docker Compose |

---

## 9. Stack reference documentation

The docs actually consulted while working on this repository, rather than a link farm.

| Link                                                                               | For                                                                                   |
| ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| <https://docs.spring.io/spring-data/relational/reference/r2dbc/query-methods.html> | R2DBC query derivation — its limits are a recurring gotcha                            |
| <https://spring.io/projects/spring-boot>                                           | Spring Boot 4                                                                         |
| <https://kotlinlang.org>                                                           | Kotlin 2.4                                                                            |
| <https://openjdk.org>                                                              | Java 25                                                                               |
| <https://vuejs.org>                                                                | Vue 3                                                                                 |
| <https://vue-i18n.intlify.dev>                                                     | Localisation (ADR-013)                                                                |
| <https://fullcalendar.io>                                                          | Calendar (ADR-011) — **keep the premium plugins out**, they are commercially licensed |
| <https://tailwindcss.com>                                                          | Styling (ADR-010)                                                                     |
| <https://jsoup.org>                                                                | Jsoup — every scraper's parser                                                        |
| <https://playwright.dev>                                                           | End-to-end tests                                                                      |
| <https://sdkman.io/>                                                               | JDK management, see `.sdkmanrc`                                                       |
| <https://www.conventionalcommits.org>                                              | Commit message format                                                                 |

---

## 10. Where the detail lives, in this repository

| Document                                                    | Answers                                                                            |
| ----------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| [`docs/ops/PLATFORM_SETUP.md`](ops/PLATFORM_SETUP.md)       | The whole platform plan — sizing, what to order, what runs where                   |
| [`docs/ops/CLUSTER_BOOTSTRAP.md`](ops/CLUSTER_BOOTSTRAP.md) | Standing a cluster up from nothing, including every hand-made secret               |
| [`docs/ops/CLUSTER_ACCESS.md`](ops/CLUSTER_ACCESS.md)       | Day-to-day access: WireGuard, kubeconfig, the database                             |
| [`docs/ops/DAILY_COMMANDS.md`](ops/DAILY_COMMANDS.md)       | The same commands with the reasoning stripped out, plus `scripts/shell-aliases.sh` |
| [`docs/ops/OPENOBSERVE.md`](ops/OPENOBSERVE.md)             | Operating OpenObserve — streams, filters, dashboards, upgrades                     |
| [`docs/ops/COSTS.md`](ops/COSTS.md)                         | What it costs to run, measured from the API rather than the price list             |
| [`docs/ops/EMAIL.md`](ops/EMAIL.md)                         | Ordering the role mailboxes at Hetzner, and the DNS records that change with them  |
| [`docs/ops/SECRETS.md`](ops/SECRETS.md)                     | SOPS + age, and why two of three secrets are encrypted into a public repo          |
| [`docs/ops/BACKUPS.md`](ops/BACKUPS.md)                     | `wal-g`, retention, and how you know it is working                                 |
| [`docs/ops/RESTORE_RUNBOOK.md`](ops/RESTORE_RUNBOOK.md)     | Restoring, including PITR                                                          |
| [`docs/ops/HEALTHCHECKS.md`](ops/HEALTHCHECKS.md)           | The dead-man's switch, and how to prove one fires                                  |
| [`docs/ops/RELEASING.md`](ops/RELEASING.md)                 | Commit → image → chart → cluster                                                   |
| [`docs/LEGAL.md`](LEGAL.md)                                 | Processors, the AVV, the imprint, what is not signed off                           |
| [`infra/README.md`](../infra/README.md)                     | The OpenTofu operator's guide, and the three things only a human can do            |
