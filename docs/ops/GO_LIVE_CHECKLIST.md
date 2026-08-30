# The go-live checklist

Read this once, in order, on launch day. It assumes you are tired.

Every line names the issue or the evidence that satisfies it. A line is done when it carries a
**date**, not when it looks true. The date is the point.

## The short version

```sh
# 1. DNS first. This publishes the apex and removes prod-check in one apply.
#    Edit publish_dns to true in infra/environments/production/variables.tf, then:
cd infra/environments/production && tofu plan -out=golive.tfplan     # READ IT

# 2. Immediately after, the chart must serve the apex instead of the rehearsal name.
#    Merge the deploy change, then stop waiting for the ten-minute poll:
flux --context event-junkie-production reconcile source oci event-junkie -n flux-system
```

**Both changes, or neither.** Section 0 explains the gap between them.

## 0 · The launch itself

Going live is **two changes in two places**. Neither works alone.

| #   | Change                                                                      | File                                           |
| --- | --------------------------------------------------------------------------- | ---------------------------------------------- |
| 1   | `publish_dns` from `false` to `true`, then apply                            | `infra/environments/production/variables.tf`   |
| 2   | `ingress.host` back to the apex, `redirectHosts` restored, `noindex: false` | `deploy/clusters/production/helm-release.yaml` |

`scripts/cluster-assertions.sh` fails the build if you do half of change 2. It ties `noindex` to the
hostname. The apex with `noindex` on is an invisible launch. The rehearsal host without it is an
unfinished site in Google.

### The gap you cannot close

`publish_dns` **swaps** rather than adds. `prod-check` disappears in the same apply that publishes the
apex. So no instant exists where both names resolve.

| Order                   | What breaks, and for how long                                                                                                          |
| ----------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| **DNS first** (do this) | The apex resolves. The Ingress still names `prod-check`. Traefik answers 404 until Flux reconciles. Minutes, and you control it.       |
| Deploy first            | The Ingress names the apex, which does not resolve yet. No certificate can issue. DNS propagation controls the length, and you do not. |

Do not publish both names for one apply to avoid this. `variables.tf` rejects that. Two lists that
could both be published is how a temporary record becomes permanent.

### Read the plan before you apply

Any edit under `infra/modules/environment/cloud-init/` since the last apply replaces **both nodes**.
`user_data` is a force-new attribute. A comment change is enough.

Expect the apply to touch `hcloud_zone_rrset` only. Stop if a server appears.

## 1 · What must be true first

### Platform

| Done       | Item                                    | Evidence                                             |
| ---------- | --------------------------------------- | ---------------------------------------------------- |
| 2026-08-21 | Production applied                      | #560                                                 |
| 2026-08-30 | Flux reconciles production              | `flux get all -A`                                    |
| 2026-08-30 | A real certificate has issued           | Let's Encrypt `CN=YR1`, not `(STAGING) Pretend Pear` |
| 2026-08-30 | The site answers over TLS from outside  | `curl https://prod-check.event-junkie.de/`           |
| 2026-08-30 | #813 patched on the database node       | `ss -lntp` names the private address                 |
|            | `tofu plan` shows no server replacement | Section 0                                            |

### Backups and recovery

| Done       | Item                                   | Evidence                    |
| ---------- | -------------------------------------- | --------------------------- |
| 2026-08-30 | `walg check` passes on production      | `ok: newest …, disk 1%`     |
| 2026-08-30 | Base backups run nightly               | `walg-basebackup.timer`     |
| 2026-08-21 | The dead-man's switch reaches a human  | `HEALTHCHECKS.md` drill log |
|            | **A restore drill against production** | `RESTORE_RUNBOOK.md` §4–5   |

The restore drill is the line most easily nodded through. `infra/AGENTS.md` calls it not optional
before go-live. The drill covers staging only, so far.

### Monitoring and alerting

| Done       | Item                                          | Evidence          |
| ---------- | --------------------------------------------- | ----------------- |
| 2026-08-21 | `walg-production` fires                       | drill log         |
| 2026-08-30 | Production records its deploys                | #872              |
|            | `site-production` created and pinging         | `HEALTHCHECKS.md` |
|            | **Production has any in-cluster monitoring**  | #880              |
|            | Alerts reach a person                         | #877              |
|            | An alert proven by breaking something on prod | #285              |

**Production has no observability of its own.** OpenObserve, the collector and the nine alert rules
run on staging and nowhere else. So the only thing that watches production is the external
healthchecks.io layer. It notices total death and nothing less than that. #880 is the port, and the
largest single item on this list.

### Content and data

| Done | Item                                                                  | Evidence |
| ---- | --------------------------------------------------------------------- | -------- |
|      | Event sources registered **and enabled**, so the site has content     | #876     |
|      | Venue addresses, districts and coordinates audited                    | #329     |
|      | Every page read in both languages, About and the legal texts included | #280     |
|      | Images served from our own cache, not hotlinked                       | #843     |

**Production serves an empty site today, and the last step is deliberate.** All 86 sources are
registered and carry their licence verdicts. Every one is disabled. A source with no import history
is always due, so enabling them starts 86 scrapes within a minute. Two venues forbid their
descriptions and images, and that had to be recorded first. Enabling them is the remaining step.
Do #843 before it — see below.

**Nothing has ever audited the venue data.** District, address and coordinates were filled in as
venues were added, with varying care. A wrong coordinate puts a pin in the wrong place, and it drops
the venue out of a radius search without saying so. The second failure is the quiet one.

**The image cache has six ordered steps, and two of them must not be combined.** #843 has the order.
Turning serving on before the backfill finishes shows a visitor broken images.

**Do it before enabling the sources, and production never hotlinks at all.** That option exists only
because the sources were left disabled. Until the cache is on, the site fetches from venue websites.
That spends their bandwidth. It also leaks a referer on every load.

**The prose is two independent documents in two languages.** The key-parity test proves every German
key exists. It cannot tell you a translation is good, or that a claim is still true.

### Legal

| Done | Item                                 | Evidence |
| ---- | ------------------------------------ | -------- |
|      | The privacy notice matches what runs | #278     |
|      | Legal review of the German notice    | #279     |
|      | Copyright status per source          | #283     |

### SEO

| Done | Item                          | Evidence  |
| ---- | ----------------------------- | --------- |
|      | `noindex` off, apex served    | Section 0 |
|      | Search Console set up         | #288      |
|      | Sitemap and hreflang accepted | #289      |
|      | Rich results tested           | #290      |
|      | Link previews checked         | #291      |
|      | Indexing watched afterwards   | #293      |

### Security

| Done | Item                                   | Evidence             |
| ---- | -------------------------------------- | -------------------- |
|      | CSP enforced, not report-only          | #854, and #843 first |
|      | Rate limiting on the public API        | #268                 |
|      | The Security tab is at zero or triaged | `/security-triage`   |

### Product

| Done | Item                              | Evidence |
| ---- | --------------------------------- | -------- |
|      | The beta badge decision           | #295     |
|      | Maintenance mode, if wanted first | #296     |

## 2 · What is deliberately not here

Items in `v1.0 — Go-live` with the `needs-deployment` label wait on a live origin. They are not
blocked on effort. Listing them makes a checklist look permanently unfinished.

**Four of them can run early**, because production serves a real hostname over a real certificate:
#290, #291, #292 and #298. Point them at `prod-check`.

Two cannot. #288 and #293 need the real domain.

## 3 · Going dark again

Revert both changes from Section 0. The apex stops resolving within one TTL, which is 300 seconds.

Two things to know before you need them:

- **HSTS `preload` is off.** It is the one setting here that is hard to undo. Removal takes months to
  reach browsers. Leave it off until the domain is settled.
- **`max-age` is one year, with `includeSubDomains`.** A browser that loaded the site once refuses
  plain HTTP for a year. Losing TLS is therefore an outage, not a degradation. That is the argument
  for the certificate being automatic.

## Related

- [CLUSTER_BOOTSTRAP.md](CLUSTER_BOOTSTRAP.md) — standing an environment up, and §12 on running dark
- [CLUSTER_ACCESS.md](CLUSTER_ACCESS.md) — reaching production day to day
- [RELEASING.md](RELEASING.md) — how a commit becomes a running deployment
- [HEALTHCHECKS.md](HEALTHCHECKS.md) — the switches that watch from outside
