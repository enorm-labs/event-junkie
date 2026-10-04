# events-admin

The operator's admin app. It runs on your computer only. It is never built into an image and never deployed ([ADR-045](../docs/adr/ADR-045_ADMIN_FRONTEND_IS_A_LOCAL_APP.md)).

## Run it

```sh
scripts/ej.sh up staging                      # repository root: the tunnel and the importer forward on 18081
cd events-admin && npm ci
npm run dev -- --mode staging                 # http://localhost:5174, /api/admin goes to localhost:18081
npm run dev -- --mode production              # the same against the production forward on 28081
```

The dev server stops with a message when the mode is not `staging` or `production`.

## Check it

```sh
npm run type-check && npm run check:lint && npm run check:format && npm run test:unit -- --run && npm run build-only
```

`build-admin.yml` runs the same commands on each pull request that changes this directory. Its required check is `Build & Test (admin)`.

## Conventions

The versions and the config follow `events-frontend/`. A Dependabot bump to one directory usually needs the same bump in the other. Components under
`src/components/ui/` are vendored shadcn-vue: add one with `npx shadcn-vue add <name>`. That command can add a Google Fonts `@import` to `src/assets/main.css`.
Remove it.

The layout comes from shadcn-vue's `dashboard-01` block. The registry has blocks only in the `new-york-v4` style, so the CLI cannot add one here.
Take a block's files from `https://shadcn-vue.com/r/styles/new-york-v4/<block>.json` by hand. Add its primitives with the CLI, in this app's style.
Tables use TanStack Table v9: `src/lib/sourceTable.ts` declares the features and the columns.

The admin API caps a page at 100 items. A list reads every page and shows an error for a partial listing (`src/api/eventSources.ts`).
