# ADR-023: Operator access is a network control, not an identity system

## Status

**Accepted (2026-09-03) — the admin API and the planned admin frontend stay unroutable, reached through `kubectl port-forward`. When an admin surface is
deployed, the first control is a Traefik `basicAuth` middleware, not an identity provider.**

Decided in [#267](https://github.com/enorm-labs/event-junkie/issues/267). **Not implemented, and deliberately so** — the control that holds today is the
routing posture already in the chart. Nothing new ships until an admin surface is deployed.

**Supersedes nothing.** It makes explicit an access model [ADR-012](ADR-012_CLOUD_PLATFORM.md) already recorded under _Admin API exposure_, which #267 and
[#340](https://github.com/enorm-labs/event-junkie/issues/340) were both written without reading. ADR-012 keeps the platform decision. This ADR answers only
who may reach an operator surface. **End-user identity is a separate question** and stays with Phase 3's
[#397](https://github.com/enorm-labs/event-junkie/issues/397).

## Context

#267 asked which authentication system to build, and listed Keycloak, Spring Authorization Server and "something lighter". The question assumed an admin
surface that a stranger could reach. There is not one, and there is no plan for one before launch.

**What forced the decision** was #340 and #341 becoming pre-launch work. That second issue warns that a
dashboard kit with its own opinionated login is expensive to fight later. So the auth shape had to be settled before a kit is chosen. That holds even
where the answer is that almost nothing gets built.

**What is true today**, checked rather than assumed:

|                     |                                                                                                                              |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| Spring Security     | Not a dependency in any module. No starter, no `SecurityConfig`, no OAuth2 client                                            |
| Admin API routing   | `ingress.yaml` routes `/api` and `/` only. `/api/admin/**` and `/actuator/**` are unreachable because nothing addresses them |
| Enforcement         | `tests/ingress_test.yaml` fails the build if that stops being true                                                           |
| Admin frontend      | Does not exist. ADR-012 records that at launch it runs locally against a port-forwarded admin API and is not deployed        |
| Traefik middlewares | Three already ship in the chart — `noindex`, `redirect`, `security-headers`                                                  |
| Operators           | One                                                                                                                          |

**The constraints any candidate had to satisfy:**

- **ADR-015's footprint limit.** It rejected `kube-prometheus-stack` on a ~1.5 GB sustained bound on a CX33. Any option adding a JVM and a database competes
  with the observability stack for the same node.
- **ADR-012's routing rule.** The admin API stays cluster-internal. Authentication is defence in depth on top of that, never a reason to relax it.
- **One operator, holding a kubeconfig.** Cluster credentials are a stronger credential than any password this system would issue to the same person.

## Candidate options

- **Keycloak.** Everything solved, passkeys included. Costs a second JVM and a database on the node ADR-015 already called tight. A full identity provider for
  one account.
- **Spring Authorization Server.** A library in our own build, no extra container and no extra database. User management, password reset and account recovery
  all become ours to write. They protect one user, who can already reach the same endpoints with `kubectl`.
- **Traefik `basicAuth` or `forwardAuth` middleware.** A fourth file in a pattern the chart uses three times, plus a secret. No new runtime, no new schema, no
  new upgrade cadence.
- **The routing posture alone.** What holds today. Free, and honest only while no admin surface is deployed.

## Comparison

|                             | New runtime    | New data        | Footprint                         | Solves it for one operator     |
| --------------------------- | -------------- | --------------- | --------------------------------- | ------------------------------ |
| Keycloak                    | JVM + database | Users, sessions | **Competes with ADR-015's bound** | Yes, at the highest cost       |
| Spring Authorization Server | None           | Users, tokens   | Small                             | Yes, after we write recovery   |
| Traefik middleware          | None           | One secret      | None                              | **Yes**                        |
| Routing posture alone       | None           | None            | None                              | Yes, while nothing is deployed |

## Decision

**Keep the routing posture as the control.** Adopt a Traefik `basicAuth` middleware as the design of record, for the moment an admin surface is deployed.

The reason that settled it: **an identity provider issues a weaker credential than the one the operator already holds.** Reaching the admin API needs a
kubeconfig for the cluster. A password that guards the same endpoints, for the same single person, adds a system to maintain and does not raise the bar.

Keycloak and Spring Authorization Server are both rejected on that ground before the footprint argument is reached. Footprint is why Keycloak would have been
rejected anyway.

## Consequences

- **Nothing ships from this ADR**, which is the intended outcome and the part that reads as inaction. The record exists so the question is not re-opened as
  though it were open.
- **#341 may choose a kit on one condition**: it must not require its own identity provider. A kit whose login screen assumes OIDC is the expensive mistake
  #341 names.
- **The routing posture is now a security control with a name.** `tests/ingress_test.yaml` is what enforces it. Changing that test is changing this decision,
  and a reviewer should treat it that way.
- **The admin frontend must not be deployed without a middleware in front of it.** That is the obligation this ADR creates, and it is easy to breach by
  accident: deploying the SPA is a chart change that looks routine.
- **A `basicAuth` secret is a hand-made credential** if it lands, with the properties `SECRETS.md` records for the others. It is not encrypted into the
  repository, because this repository is public.
- **The unwelcome half.** Basic auth over a browser session is a poor experience, has no session management and no revocation short of rotating the secret.
  That is acceptable for one operator on a surface used from a laptop, and it stops being acceptable the moment a second person needs access.

## When to revisit

Any one of these reverses the reasoning above, because each removes the "one operator holding a kubeconfig" premise:

- **A second operator**, or anyone who should reach the admin surface without cluster credentials.
- **The admin surface becoming reachable from the internet** rather than from a port-forward or an allowlisted address.
- **End-user accounts arriving**, if #397 decides personalization needs them. An identity system existing for other reasons changes the arithmetic entirely.
- **Any admin capability that a kubeconfig does not already grant.** Today the admin API can do nothing its holder could not do with `kubectl`. If that stops
  being true, the credential comparison this ADR rests on stops being true with it.

## References

- [#267](https://github.com/enorm-labs/event-junkie/issues/267) — the decision issue
- [ADR-012](ADR-012_CLOUD_PLATFORM.md) § _Admin API exposure_ — the access model this makes explicit
- [ADR-015](ADR-015_OBSERVABILITY_STACK.md) — the footprint bound that rules out a second JVM and database
- [#340](https://github.com/enorm-labs/event-junkie/issues/340) · [#341](https://github.com/enorm-labs/event-junkie/issues/341) — the admin frontend and its kit
- [#397](https://github.com/enorm-labs/event-junkie/issues/397) — end-user identity, deliberately not decided here
