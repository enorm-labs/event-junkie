---
slug: terraform-iac
title: Provision the cloud environment with Terraform / OpenTofu
type: Task
milestone: v0.2 — Deployable
labels: ["area:infra", "size:L", "blocked"]
priority: P0
status: Blocked
blocked-by: [accept-adr-012]
---

Provision the environment reproducibly instead of by hand. Per ADR-012: the `hetznercloud/hcloud`
provider for servers, networks, firewalls and volumes, with state in Hetzner Object Storage (S3
API) or Terraform Cloud.

Doing this before the first deploy rather than after is the whole point — a hand-built server is a
server nobody can rebuild under pressure, and the restore drill this milestone also asks for is
only meaningful if the thing being restored *onto* is reproducible.

**Done when**

- [ ] Servers, networks, firewalls and volumes are declared, not clicked
- [ ] State lives somewhere durable and shared, not on a laptop
- [ ] A destroy/apply cycle produces a working environment

**References** — `docs/adr/ADR-012_CLOUD_PLATFORM.md`
