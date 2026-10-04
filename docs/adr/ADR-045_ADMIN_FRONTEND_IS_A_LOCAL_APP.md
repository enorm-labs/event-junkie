# ADR-045: The admin frontend is a separate local app built with shadcn-vue

## Status

**Accepted (2026-10-04) — the admin frontend is `events-admin/`, a Vite, Vue and shadcn-vue app that runs only on the operator's computer. It is never built
into an image and never deployed. Its dev server sends `/api/admin` to the importer port-forward that `scripts/ej.sh up <env>` opens.**

Decided in [#341](https://github.com/enorm-labs/event-junkie/issues/341). **Implemented in #341** as the app shell and one read-only page, the list of event
sources.

**Does not supersede anything.** [ADR-023](ADR-023_OPERATOR_AUTHENTICATION.md) decides who can reach the admin API. This ADR uses that access
model and does not change it. [ADR-010](ADR-010_FRONTEND_STYLING_FRAMEWORK.md) chose shadcn-vue for the public site. This ADR uses the same kit for the admin app.

## Context

The admin work in [#340](https://github.com/enorm-labs/event-junkie/issues/340) needs a place to live. #342, #343, #345, #346 and #347 all need a shell
first. If two screens are built by hand before a kit is chosen, the hand-built screens usually stay.

The constraints:

- **No identity provider.** ADR-023 keeps the admin API off the internet. Access is a `kubectl port-forward`, and a kit with its own login screen would fight
  that model.
- **Nothing admin in the public bundle.** The public site is the product. Admin code in its bundle adds weight for every visitor and exposes admin routes.
- **No new licence review.** `events-frontend` already uses shadcn-vue and reka-ui, and `notices.json` covers them.
- **The same toolchain.** The project already maintains Vite 8, Vue 3, TypeScript 6 and Tailwind CSS 4. A second toolchain doubles the update work.

## Candidate options

1. **Admin routes inside `events-frontend`.** One build and one set of dependencies. The admin code then ships to every visitor unless a second build target
   removes it. The admin API is on another port, so the dev proxy and the production nginx both need a second route.
2. **A separate local app, `events-admin/`.** It has its own `package.json`. Its versions are the same as in `events-frontend`. Nothing in it reaches
   an image.
3. **A ready-made admin template.** Most templates ship a login screen, a route guard and a design system of their own. That is the conflict with ADR-023
   that #341 warns about.

## Decision

**Option 2, with shadcn-vue as the kit.** The reason that settled it: a separate local app cannot leak into the public bundle or into a deployment. That
holds without a build flag, a review or a chart test.

The layout is shadcn-vue's own `dashboard-01` block: a sidebar, a header and a TanStack data table. The block is part of the kit, not a third-party
template, and it has no login screen. The app does not use its demo charts, cards and user menu.

The rules:

1. **`events-admin/` runs only through `npm run dev -- --mode <env>`.** The mode is `staging` or `production`. Any other mode stops the dev server with a
   message.
2. **The dev server proxies `/api/admin` and nothing else.** The target is `localhost:18081` for staging and `localhost:28081` for production, the ports of
   the forwards in `scripts/ej.sh`.
3. **No Dockerfile, no chart entry and no publishing workflow.** A deployed admin surface needs the Traefik middleware that ADR-023 requires first.
4. **The versions follow `events-frontend`.** Dependabot watches `/events-admin` as a separate npm directory.

## Consequences

- **Each admin page reads all pages of a listing.** The admin API returns at most 100 items per page, and production has more than 100 sources. The source
  list pages until it has `totalElements` items, and it shows an error for a partial listing.
- **Two npm projects to keep current.** Dependabot opens separate pull requests for `/events-admin`. The versions can drift from `events-frontend` if one
  set of pull requests is merged and the other is not.
- **The admin API types are hand-written for now.** No generated schema covers the importer. A later change can generate one from the importer's OpenAPI
  document, as `scripts/api-schema-parity.sh` does for the BFF.
- **No CI job builds an image of `events-admin`.** CodeQL scans it. `build-admin.yml` runs the type-check, the lint and the unit tests on each pull request
  that changes it ([#2635](https://github.com/enorm-labs/event-junkie/issues/2635)).
- **The admin app is not distributed.** It runs on the operator's computer only. So its dependencies need no entry in `notices.json`.

## When to revisit

- **A second operator**, or anyone who needs the admin app without cluster credentials. ADR-023 then changes too.
- **A decision to deploy the admin surface.** That needs an image, a chart entry and the ADR-023 middleware.

## References

- [#341](https://github.com/enorm-labs/event-junkie/issues/341) — this decision
- [#340](https://github.com/enorm-labs/event-junkie/issues/340) — the admin frontend
- [ADR-023](ADR-023_OPERATOR_AUTHENTICATION.md) — operator access through a port-forward
- [ADR-010](ADR-010_FRONTEND_STYLING_FRAMEWORK.md) — shadcn-vue for the public site
- `scripts/ej.sh` — the port-forwards per environment
- `scripts/force-import.py` `sources()` — the same paging rule in Python
