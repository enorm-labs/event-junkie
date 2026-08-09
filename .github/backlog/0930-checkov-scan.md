---
slug: checkov-scan
title: Evaluate a Checkov scan for the infrastructure code
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:security", "area:ci", "size:S", "blocked"]
priority: P2
status: Blocked
blocked-by: [terraform-iac]
---

Static analysis for Terraform and Kubernetes manifests — misconfigured security groups, public
buckets, containers running as root.

**Only worth it once there is infrastructure code to scan**, which is why it is blocked rather than
merely unstarted. Adding it to an empty repository produces a green check that means nothing and
then gets trusted.

Timebox the evaluation: the question is whether its findings are actionable on a k3s-on-Hetzner
setup, or whether it is tuned for the big clouds and will mostly emit noise.
