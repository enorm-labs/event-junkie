# ADR-033: A post-deploy check runs where its environment is reachable, and nothing in Actions reaches staging

## Status

**Accepted (2026-09-21) — a check against a deployed environment runs where that environment is reachable. Staging is reachable only from inside its own
cluster, so its checks run there, as `helm test` hooks. Production is public, so its checks may also run from GitHub Actions. A check that needs a real build
but no particular environment runs against the chart on k3d in CI. No workflow gets a route into staging, not through a WireGuard key and not through a
self-hosted runner.**

**Implemented in part.** The first hook, `templates/tests/connection-test.yaml`, runs on both clusters since [#414][414]. The smoke hook is the pull
request that closes [#1697][1697]. The k3d browser suite is [#1699][1699] and the production Lighthouse run is [#1698][1698].

**Does not supersede anything.** [ADR-016](ADR-016_GITOPS_DELIVERY.md) states one line of this: "verification runs where the workloads do". It did not say
which routes into staging were refused, or where a check that is not a hook belongs. [ADR-021](ADR-021_PUBLIC_SITE_MONITORING.md) decided how production is
watched from outside. It did not decide where a test suite runs. Both stand.

## Context

[#298][298] is open since staging existed. Its 2026-08-13 note names the one hard part. A scheduled workflow cannot `curl` staging. So each suite either runs
in-cluster as a job, or the workflow reaches staging through the tunnel. The note asks to "decide that once, for all three". Nobody did, and the three
suites it names, k6, Playwright and a smoke, stayed on demand.

Staging is dark on purpose. [#265][265] gave it no public `A` record and bound its Ingress to the WireGuard tunnel. [PLATFORM_SETUP.md
§4a](../ops/PLATFORM_SETUP.md) records the reason: a crawler cannot index what does not resolve, and no `basicAuth` middleware can be misplaced. The same section
records the cost, in one sentence: "CI cannot reach staging either, so post-deploy smoke tests cannot run from GitHub Actions."

The admin API is what makes a route into staging expensive. The header of `deploy/charts/event-junkie/templates/networkpolicy.yaml` says it. Nothing reaches
the importer. Its admin API "was unroutable, now it is unreachable from inside the namespace too". [#267][267] is open because the tunnel alone keeps that API
private. Any credential that opens the tunnel opens the admin API with it.

**The constraints any candidate had to satisfy:**

| Constraint                                                                          | Fixed by                                                                                     |
| ----------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| CI holds no credential into a cluster                                               | ADR-016, and the `main` ruleset that replaced the kubeconfig ([#443][443])                   |
| Staging has no public name and no public port                                       | [#265][265], `infra/environments/staging` with `publish_dns` and `public_web` false          |
| The admin API is protected by reachability alone                                    | `networkpolicy.yaml`, [#267][267]                                                            |
| A failed post-deploy check rolls the release back without a person                  | `helm-release.yaml` on both clusters, `test.enable` and `remediateLastFailure` ([#414][414]) |
| The node that serves the site has four vCPUs and serves the site while it is tested | `infra/environments/*/main.tf`, `k3s_server_type = "cx33"`                                   |

## Candidate options

1. **A WireGuard peer in Actions.** A repository secret holds a tunnel key. Every workflow that can read the secret can reach the node, the admin API and
   PostgreSQL's private address. A `pull_request_target` job or a compromised action reads secrets. This turns [#267][267] from a defence-in-depth item into
   the only defence.
2. **A self-hosted runner on the staging node.** The runner is registered to a public repository. GitHub's own documentation refuses this shape: a fork's
   workflow can run on it. The host is also the node that serves staging. A runner job competes with the site for the four vCPUs it measures.
3. **In-cluster, as `helm test` hooks.** The hook pod runs where the workloads run, after every install and upgrade, and Flux rolls back on failure. No
   credential leaves the cluster. The cost is the same node again, so the hook has to stay cheap. A flaky hook rolls back a release that was fine.
4. **The chart on k3d, in CI.** `dast.yml` already creates a k3d cluster through Flux nightly, from the published snapshot chart ([#1421][1421]). It runs the
   images and Traefik middlewares that Hetzner runs. A browser suite there sees a real nginx, a real CSP and a real ingress. It does not see staging's data or
   staging's certificate.
5. **Production from Actions.** Production is public, and `site-probe.yml` and `dast.yml` already send it HTTPS with `contents: read`. A read-only check from
   a runner adds nothing to its attack surface. A load test would, so a load test is not this option.

## Comparison

| Axis                                    | 1. WireGuard peer | 2. Self-hosted runner | 3. Hook in-cluster | 4. k3d in CI    | 5. Production from Actions |
| --------------------------------------- | ----------------- | --------------------- | ------------------ | --------------- | -------------------------- |
| New credential into a cluster           | one, in Actions   | one, on the node      | none               | none            | none                       |
| Reaches the admin API                   | yes               | yes                   | only if written    | only if written | no                         |
| Runs after every deployment             | yes, if triggered | yes, if triggered     | yes, by Flux       | no, nightly     | yes, via the dispatch      |
| Sees staging's data and certificate     | yes               | yes                   | yes                | no              | not staging's              |
| Load on the node under test             | none              | the whole job         | the hook pod       | none            | the requests only          |
| Fails the release back without a person | no                | no                    | yes                | no              | no                         |
| Room for a browser suite                | yes               | yes                   | no                 | yes             | yes, read-only             |

## Decision

Options 3, 4 and 5 together, one per kind of check. Options 1 and 2 are refused, and the refusal is the part worth writing down.

| Check                              | Runs                                                                    | Because                                                                              |
| ---------------------------------- | ----------------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| Post-deploy smoke, both clusters   | `helm test` hooks, `templates/tests/*.yaml` ([#1697][1697])             | after every reconcile, rollback on failure, no credential, cheap enough for the node |
| Browser suite against a real build | k3d in CI, beside `dast-k3d` ([#1699][1699])                            | a real Traefik, nginx and CSP without a route into staging                           |
| Lighthouse                         | production, from Actions, after a production deployment ([#1698][1698]) | public, read-only, and the only origin with real caching and compression             |
| k6 load and spike                  | on demand, over the tunnel, from a machine that is not the node         | a load generator on the four vCPUs it measures is not a measurement ([#298][298])    |

What settled it: the two refused options each trade the admin API's only protection for a test that the hook can run without the trade.

## Consequences

- **The hook is a rollback, so it must not flake.** A latency threshold written for a laptop is a rollback waiting for a cold JVM. The smoke hook asserts
  status and shape, with a wide budget. Production takes it only after a week of staging reconciles without a false rollback ([#1697][1697]).
- **Anything heavier than a smoke does not go in the hook.** A browser image is about two gigabytes and five browsers on the node that serves the site. That
  suite runs on k3d, and its findings arrive a night later, not with the deployment.
- **Staging's certificate is not verified by its hook.** Let's Encrypt's staging CA is untrusted on purpose, so `tests.smoke.verifyTls` is false there. An
  unissued certificate on staging is found by a person on the tunnel, as today. Production verifies.
- **A staging-only failure that a hook cannot express stays a manual find.** A visual regression, a translation, a slow page. The k3d suite covers the build,
  and a person on the tunnel covers the rest.
- **Trend data comes from the log pipeline, not from a store this ADR adds.** Each hook writes one JSON line to stdout, and the collector ships it. [#298][298]
  step 2, a metric store for durations, is still open.
- **If staging is ever made reachable for a person**, PLATFORM_SETUP §4a names the two shapes, a WireGuard config or a `basicAuth` Ingress. Neither of those
  is a route for Actions, and this ADR is the reason.

## When to revisit

- **[#267][267] lands**, and the admin API has authentication of its own. Option 1's cost drops to a tunnel key alone. It is still a credential into a cluster
  that ADR-016 removed on purpose, so the answer is likely unchanged. The comparison table is what to re-read.
- **Staging moves to a node that does not serve the site**, or gains a second node. Option 2's load argument goes away. Its public-repository argument does not.

## References

- [#298][298] — the umbrella, with the 2026-08-13 note that asked for this decision
- [#265][265] — staging without a public name
- [#267][267] — the admin API's missing authentication
- [#414][414] — Flux's `test.enable` and rollback
- [#443][443] — branch protection as the control that replaced the kubeconfig
- [#1421][1421] — the k3d cluster in CI, through Flux
- [#1697][1697], [#1698][1698], [#1699][1699] — the three checks
- [GitHub Docs — self-hosted runner security with public repositories](https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions#hardening-for-self-hosted-runners)
- [ADR-016](ADR-016_GITOPS_DELIVERY.md), [ADR-021](ADR-021_PUBLIC_SITE_MONITORING.md)

[265]: https://github.com/enorm-labs/event-junkie/issues/265
[267]: https://github.com/enorm-labs/event-junkie/issues/267
[298]: https://github.com/enorm-labs/event-junkie/issues/298
[414]: https://github.com/enorm-labs/event-junkie/issues/414
[443]: https://github.com/enorm-labs/event-junkie/issues/443
[1421]: https://github.com/enorm-labs/event-junkie/issues/1421
[1697]: https://github.com/enorm-labs/event-junkie/issues/1697
[1698]: https://github.com/enorm-labs/event-junkie/issues/1698
[1699]: https://github.com/enorm-labs/event-junkie/issues/1699
