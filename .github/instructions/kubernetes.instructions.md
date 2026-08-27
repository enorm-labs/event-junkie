---
applyTo: "deploy/**/*.yaml"
paths:
    - "deploy/**/*.yaml"
---

# Kubernetes' Own Good Practices, Audited

The chart and the cluster manifests against Kubernetes' published guidance. The safety rule that governs this directory — **rendering the chart is always safe,
installing it is never your own initiative** — is in [deploy/AGENTS.md](../../deploy/AGENTS.md), which stays loaded for the whole subtree and is not optional
reading.

Worked through [Configuration Best Practices](https://kubernetes.io/docs/concepts/configuration/overview/) and its
[2025 restatement](https://kubernetes.io/blog/2025/11/25/configuration-good-practices/), plus [Setup Best
Practices](https://kubernetes.io/docs/setup/best-practices/). Most of it the chart already did; what is written down here is the part that **changed something**
or that a future reader would otherwise "fix" back.

**Applied:**

- **Latest stable API versions** — `apps/v1`, `v1`, `networking.k8s.io/v1`. One exception, and it is not a stale pin: `traefik.io/v1alpha1` is what Traefik
  publishes for `Middleware`; there is no v1. Alpha APIs carry no deprecation guarantee, so if Traefik moves it, the redirect middleware is the single object
  affected — and it is already gated on `ingress.redirectHosts` being non-empty.
- **No redundant defaults.** `protocol: TCP` was stated on all ten ports and is the API default; it is gone. Do not put it back.
- **`kubernetes.io/description` on every object a human might have to reason about.** This one is worth understanding rather than copying: the Go-template
  comments in `templates/` are extensive and **all of them vanish at render time**. An operator running `kubectl describe` on a live Deployment at 23:00 sees
  none of it. The annotation persists into the API, which is the only place that reader is looking.
- **The YAML boolean trap, already avoided — keep it that way.** `SPRING_MAIN_BANNER_MODE: "off"` in the ConfigMap is quoted deliberately. Unquoted, YAML reads
  `off` as boolean `false`, and ConfigMap `data` values must be strings, so it does not merely change meaning — it fails to render. The same applies to any
  future `yes`/`no`/`on`/`y`/`n` value.
- **No naked Pods**, with one deliberate exception: `templates/tests/connection-test.yaml` is a bare Pod because that is Helm's documented test-hook shape. A
  controller would actively defeat it — the hook's exit code _is_ the test result, and something that restarts the pod would destroy the signal.

**Two deliberate deviations, so nobody re-litigates them:**

1. **One resource per file, not grouped manifests.** Kubernetes' guidance says to keep related Deployments, Services and ConfigMaps in a single file so they can
   be applied as a unit; Helm's chart guide says one resource per file with the kind in the filename. **Helm wins here, because these are templates, not
   manifests.** Nobody ever runs `kubectl apply -f` against them — Helm concatenates them at render — and "apply as a unit" is precisely what a release already
   is. The grouping benefit is already provided by the thing that makes them a chart.
2. **`type: ClusterIP` stated although it is the default.** On `importer-service.yaml` this is a security statement rather than configuration: the admin API has
   no authentication of its own, and what keeps it private is that nothing outside the cluster can address it — one word changed to `NodePort` undoes that. It
   is kept on all three Services so the three do not differ in shape for reasons a reader has to reconstruct.

**Pod Security Standards: the chart satisfies `restricted` in full**, today, without the namespace label that would enforce it. `runAsNonRoot`,
`allowPrivilegeEscalation: false`, `readOnlyRootFilesystem`, `capabilities.drop: [ALL]`, `seccompProfile: RuntimeDefault`, no host namespaces, no host ports, no
privileged containers, and only `emptyDir`/`configMap`/`secret` volumes. `tests/invariants_test.yaml` and `tests/hardening_test.yaml` assert each of those, which is what keeps compliance
deliberate rather than incidental — **until #416 adds the label, nothing rejects a violation at admission**, so a workload could drift and only fail on the day
the label lands.

**What does not apply**, recorded so the setup guide is not re-read from scratch: _large clusters_ and _multiple zones_ (one node, one zone — ADR-012), _node
conformance_ (k3s owns it), and _PKI certificates_ (k3s owns the cluster PKI; the public certificate is cert-manager's, #265).
