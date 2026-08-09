---
slug: auth-authz
title: Add authentication and authorization
type: Feature
milestone: v0.3 — Launch-ready
labels: ["area:security", "area:bff", "size:L", "needs-decision"]
priority: P0
status: Backlog
---

**As** an operator
**I want** the admin surfaces to require a login and a role
**so that** import control, source configuration and data editing are not open to whoever finds the URL.

Today the importer's admin API is protected only by not being routed publicly (see the Helm chart
issue). That is a deployment-shaped answer to an application-shaped problem, and it stops being
sufficient the moment an admin frontend exists.

**Open questions to settle first** — this is why the issue carries `needs-decision`:

- What is the Spring-native best practice here, and does it change on WebFlux?
- Keycloak, at least locally for testing? Or something lighter until there are real user accounts?
- Passkey support — worth building in from the start, or an addition later?

Note the sequencing trap: **Phase 3's follows and favourites may not need accounts at all** (see
the epic). Deciding *this* auth story around operator access only, and leaving end-user identity to
that decision, avoids building an account system twice.
