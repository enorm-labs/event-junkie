---
slug: typescript-7-upgrade
title: TypeScript 7 — blocked on vue-tsc, not on us
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "blocked", "size:S"]
priority: P2
status: Blocked
---

TS 7 — the native Go port — restructured the package and no longer exports `typescript/lib/tsc`,
which `vue-tsc@3.3.9` requires. `npm run type-check` and `npm run build` both die with
`ERR_PACKAGE_PATH_NOT_EXPORTED`.

**There is no newer `vue-tsc`** — 3.3.9 is latest — and its peer range (`typescript >=5.0.0`) is
simply stale, so **npm installs the combination happily and it fails at run time**. That is worth
knowing before someone tries the upgrade and concludes the repo is misconfigured.

**Recheck when `@vue/language-tools` ships TS 7 support.** The upgrade itself is a one-line version
bump once it does.

**Related, and the same class of problem:** `openapi-typescript` declares `peer typescript@^5.x`,
which is why `generate:api` runs through `npx` in isolation rather than as a devDependency.
