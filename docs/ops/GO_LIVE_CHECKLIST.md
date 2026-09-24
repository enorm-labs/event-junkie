# The go-live checklist

Read this once, in order, on launch day. It assumes you are tired.

Every line names the issue or the evidence that satisfies it. A line is done when it carries a
**date**, not when it looks true. The date is the point.

## The short version

```sh
# 1. DNS first. This publishes the apex and removes prod-check in one apply.
#    Edit publish_dns to true in infra/environments/production/variables.tf, then target the
#    records: an untargeted plan also rebuilds both nodes while user_data has drifted (infra/AGENTS.md).
cd infra/environments/production && tofu plan -out=golive.tfplan \
  -target=hcloud_zone_rrset.address -target=hcloud_zone_rrset.redirect     # READ IT

# 2. Immediately after, the chart must serve the apex instead of the rehearsal name.
#    Merge the deploy change, then stop waiting for the ten-minute poll. The values come from git:
flux --context event-junkie-production reconcile kustomization flux-system --with-source
flux --context event-junkie-production reconcile helmrelease event-junkie -n flux-system
```

**Both changes, or neither.** Section 0 explains the gap between them.

## 0 · The launch itself

Going live is **four changes in four places**. The first two serve the site. The last two stop the monitoring from
alarming about it.

| #   | Change                                                                      | Where                                          |
| --- | --------------------------------------------------------------------------- | ---------------------------------------------- |
| 1   | `publish_dns` from `false` to `true`, then apply                            | `infra/environments/production/variables.tf`   |
| 2   | `ingress.host` back to the apex, `redirectHosts` restored, `noindex: false` | `deploy/clusters/production/helm-release.yaml` |
| 3   | **Delete the `SITE_URL` repository variable**                               | GitHub → Settings → Variables                  |
| 4   | **Point the Better Stack monitor at the apex**                              | the Better Stack console, monitor `4876693`    |

**Changes 3 and 4 are not tidying, and forgetting them alarms you on launch day.** `prod-check.event-junkie.de` stops
resolving the moment change 1 applies. The daily probe then fails against a name that is gone and pings
healthchecks.io `/fail`. The Better Stack monitor reports the site down while it is up. Two false alarms, in the hour
you least want them.

Change 3 is a **deletion**, not an edit. `site-probe.yml` falls back to the apex when the variable is absent, and that
is the value that should survive somebody forgetting this page exists.

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

**Target the records.** `user_data` on both production nodes differs from the code, so an untargeted plan replaces them
(`infra/AGENTS.md`). The records read the Primary IPs and not the servers, so
`-target=hcloud_zone_rrset.address -target=hcloud_zone_rrset.redirect` plans DNS alone.

Expect 8 to add and 2 to destroy, all `hcloud_zone_rrset`: `@` and `www` on both domains, A and AAAA, and
`prod-check` removed. Stop if a server appears.

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

| Done       | Item                                   | Evidence                        |
| ---------- | -------------------------------------- | ------------------------------- |
| 2026-08-30 | `walg check` passes on production      | `ok: newest …, disk 1%`         |
| 2026-08-30 | Base backups run nightly               | `walg-basebackup.timer`         |
| 2026-08-21 | The dead-man's switch reaches a human  | `HEALTHCHECKS.md` drill log     |
| 2026-09-24 | **A restore drill against production** | #1636, `BACKUPS.md` §9, run log |

The restore drill is the line most easily nodded through. `infra/AGENTS.md` calls it not optional
before go-live.

### Monitoring and alerting

| Done       | Item                                                     | Evidence                           |
| ---------- | -------------------------------------------------------- | ---------------------------------- |
| 2026-08-21 | `walg-production` fires                                  | drill log                          |
| 2026-08-30 | Production records its deploys                           | #872                               |
| 2026-08-31 | A decision on how the site is watched from outside       | ADR-021                            |
| 2026-08-31 | **The Better Stack monitor exists and polls production** | ADR-021, HEALTHCHECKS.md           |
| 2026-08-31 | That monitor proven by inducing a failure                | HEALTHCHECKS.md drill log          |
|            | The monitor and `SITE_URL` both name the apex            | Section 0, changes 3 and 4         |
| 2026-09-06 | Decide how visitors and traffic are counted              | #1126 — page loads, nothing new    |
| 2026-08-31 | **Production has any in-cluster monitoring**             | #880, and the dashboard push below |
| 2026-09-24 | Alerts reach a person                                    | #877, OPENOBSERVE.md drill log     |
| 2026-08-31 | An alert proven by breaking something on prod            | #285                               |

**Production has its own observability now** (#880, closed). It runs OpenObserve, the collector agent and gateway,
the OTel operator and `postgres-exporter`. It carries all twelve alert rules. So the external layer below is no
longer the only thing watching production. That layer still gives one thing an in-cluster stack cannot: a view from
**outside** the cluster.

**Two layers are pushed by hand, and a missing one is an empty list, not an error.** OpenObserve dashboards and
alert rules are API objects, so Flux cannot reconcile them (`OPENOBSERVE.md`, the seam where GitOps stops). **After
any production rebuild, and after any change to either file, run both.** `EJ_NODE` selects the cluster and defaults
to **staging**, so omitting it succeeds against the wrong one and says nothing:

```sh
cd deploy/dashboards && EJ_NODE=ops@10.10.0.1 ./apply.sh --diff   # is production running this file at all?
cd deploy/alerts     && EJ_NODE=ops@10.10.0.1 ./apply.sh --diff   # the same question, for the rules
```

`--diff` answers "is it there", `--check` answers "do its queries return data", and neither substitutes for the
other. Drop the flag to push. Both are idempotent — the dashboard import matches on title and replaces.

**The external layer is a Better Stack monitor** (ADR-021). It polls every three minutes and alerts in about six. A
drill proved it, by changing its keyword to a string the site does not serve. `site-probe.yml` stays as a daily
dead-man's switch and asserts the monitor's settings against the repository. **The row _Alerts reach a person_ above
refers to the in-cluster path** (#877, OpenObserve to e-mail). A firing produced a mail on each cluster on 2026-09-24.

### Content and data

| Done       | Item                                                                                                      | Evidence     |
| ---------- | --------------------------------------------------------------------------------------------------------- | ------------ |
| 2026-08-30 | Event sources registered **and enabled**, so the site has content                                         | #876         |
| 2026-08-30 | Venue addresses, districts and coordinates audited                                                        | #329         |
| 2026-09-07 | Venue descriptions read against the venue they describe                                                   | #1124        |
|            | Venue descriptions proof-read once more **by the maintainer**, in both languages, before the flip         | #1124, #1210 |
|            | Every page read in both languages **by the maintainer**, as a reader — About and the legal texts included | #280         |
| 2026-08-31 | Images served from our own cache, not hotlinked                                                           | #843         |
| 2026-09-07 | Multilingual event text decided, so the translation question is answered before a venue is asked          | #469         |
| 2026-09-08 | Every description a venue has not prohibited translated on production                                     | #470         |
|            | Machine-translated descriptions read on the site **by the maintainer**, in German and English             | ADR-027      |

**Production serves the full catalogue.** Every source is registered, enabled and carries its licence verdict. The
importer runs on schedule. Images come from the cache (#843). Two venues forbid their descriptions and images, and
that was recorded before the sources were enabled.

**A wrong coordinate drops a venue out of a radius search without saying so.** That is the quiet failure the address
audit (#329, #986) looked for. **The venue descriptions were read against the venue, not the address** (#1124). A
corrected address does not correct the sentence that quotes it. The faults found were a building the venue never
occupied, or a genre the house does not play. The unsettled facts are on #1196. The maintainer's own read of all 86
before the flip is the row above.

**Translations follow the display rule** (ADR-027). `translation_licence` is `PERMITTED` on every source except the
two that prohibit the description itself. Every translation carries the engine, its version and a hash of its input,
so a poor one is regenerated rather than repaired. The plausibility checks (#1213) reject a summary or a lost proper
noun. They cannot tell you a sentence is merely bad German. That is why the row above is a human read.

**The prose is two independent documents in two languages.** The key-parity test proves every German
key exists. It cannot tell you a translation is good, or that a claim is still true.

**Machine translation is on, and it publishes derivative works of 84 venues' text.** ADR-027 decided that translation
follows the display rule. Silence permits it, exactly as it permits showing the description. ADR-026 carries the case
against, and neither document is a legal opinion.

Two things make the row above worth doing rather than nodding through. The output is public and nobody read a sample
of it yet. A poor translation also passes the checks in #1213, which reject only a summary or a lost proper noun. So
read a handful on `/de/` and on `/en/` before the flip. §5 of `SCRAPING_POSITION.md` answers a venue that objects, the
same day. `PROHIBITED` on either licence field then removes every translation for that source at once.

### Legal

| Done       | Item                                        | Evidence |
| ---------- | ------------------------------------------- | -------- |
| 2026-09-02 | The privacy notice matches what runs        | #278     |
| 2026-09-09 | Legal review of the German notice           | #279     |
| 2026-09-09 | The translation processor, disclosed or off | #1233    |
| 2026-08-30 | Copyright status per source                 | #283     |

### SEO

| Done       | Item                | Evidence |
| ---------- | ------------------- | -------- |
| 2026-09-07 | Rich results tested | #290     |

**The Rich Results Test passed on two real event pages, in code mode.** URL mode cannot run against
`prod-check`: its `robots.txt` says `Disallow: /`, and the test reports the crawl as failed. The
JSON-LD each page renders was submitted as is. Both a `MusicEvent` with an offer and a `SocialEvent`
without one came back valid, with the breadcrumb list valid beside them. Every warning is optional
and accepted. `endDate` and `offers.validFrom` are not in the data. `organizer` and `offers` are
emitted when a promoter or a price exists. Structured data may only say what the page shows. URL mode
runs again against the apex, in Section 2.

### Security

| Done       | Item                                   | Evidence             |
| ---------- | -------------------------------------- | -------------------- |
| 2026-09-07 | CSP enforced, not report-only          | #854, and #843 first |
| 2026-09-03 | Rate limiting on the public API        | #268                 |
|            | The Security tab is at zero or triaged | `/security-triage`   |

### Product

| Done | Item                                                                                                                                 | Evidence |
| ---- | ------------------------------------------------------------------------------------------------------------------------------------ | -------- |
|      | Maintenance mode, if wanted first                                                                                                    | #296     |
|      | The repo health files proof-read **by the maintainer** — README, CONTRIBUTING, SUPPORT, SECURITY, the Code of Conduct, the templates | #281     |

## 2 · After go-live

The apex resolves and the site is public. These are the acts that could not happen before that. The
section exists so they are not lost in the relief of the flip working.

| Done | Item                                                                 | Evidence                 |
| ---- | -------------------------------------------------------------------- | ------------------------ |
|      | `noindex` off and the apex served, confirmed against the live origin | Section 0                |
|      | Search Console set up                                                | #288                     |
|      | Sitemap and hreflang accepted                                        | #289                     |
|      | Rich Results Test re-run in URL mode against the apex                | #290                     |
|      | Link previews checked in Slack, WhatsApp and iMessage                | #291                     |
|      | The Better Stack monitor re-proved against the apex                  | ADR-021                  |
|      | The first nightly plausibility run green against the apex            | `agent-plausibility.yml` |
|      | The venue licence enquiry sent, first batch of twelve                | #808                     |
|      | Launch marketing, venues first                                       | #481, Phase 2            |
|      | The beta badge decision, and the README rewritten around it          | #295                     |
|      | Whether to publish an uptime badge                                   | HEALTHCHECKS.md          |
|      | Whether to turn HSTS `preload` on, once the domain is settled        | Section 4                |
|      | Indexing watched, especially of detail pages                         | #293                     |
|      | The k6 runs automated against a real origin                          | #298, Phase 2            |
|      | Session weights re-derived from real traffic                         | #297                     |

**The first five are launch day, or the morning after.** Each needs a name that resolves for somebody
other than us. That is the whole reason they are here rather than in Section 1.

**The licence enquiry waits for the apex because the mail links a venue's own page.** A link that
fails makes an enquiry look like a pitch, and that reading is what § 7 UWG punishes.
[`docs/licence-review/ENQUIRY.md`](../licence-review/ENQUIRY.md) carries the three mails, the first
batch of twelve, and how a reply is written back.

**The README claims a status in two places, and both are wrong the moment the apex serves.** The
badge near the top reads `Status-In Development`, and § Status opens with "In development — deployed,
but not public yet." Change the badge to `Status-Live-brightgreen`, and rewrite the section around
what production serves. One without the other leaves the page contradicting itself.

**Two of these prove that the flip did not break the watching.** Changes 3 and 4 in Section 0 repoint
the monitor and the daily probe at the apex. Neither is proved by being repointed. The monitor gets a
second induced failure, and the first nightly plausibility run has to come back green against the new
name.

**Three need weeks rather than hours.** Indexing, the k6 runs and the session weights are the items
launch day cannot finish. The last two are `Phase 2` issues and sit here as acts, not as gates.

**The two mails go in this order, and not together.** The licence enquiry is administrative. The
marketing mail is not. § 7 UWG is the reason to keep them apart. #481 sits below #808 here for the
same reason it moved to `Phase 2`. It is not a launch gate.

## 3 · What is deliberately not here

Items in `v1.0 — Go-live` with the `needs-deployment` label wait on a live origin. They are not
blocked on effort, and Section 2 is where the ones that matter reappear as acts rather than as
issues.

**Four of them can run early**, because production serves a real hostname over a real certificate:
#290, #291, #292 and #298. Point them at `prod-check`.

Two cannot. #288 and #293 need the real domain.

## 4 · Going dark again

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
