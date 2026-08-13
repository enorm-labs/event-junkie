# Event Junkie Frontend

Displays events (today's event overview, week overview, month overview), provides an event calendar and allows to search
for events.

The frontend is built with Vue and uses the [events-bff](../events-bff) as backend. Styling uses
[Tailwind CSS v4](https://tailwindcss.com) with [shadcn-vue](https://www.shadcn-vue.com) components — see
[ADR-010](../docs/adr/ADR-010_FRONTEND_STYLING_FRAMEWORK.md) for the rationale.

## Development

### Setup Node.js

- Install nvm to manage Node.js versions: https://github.com/nvm-sh/nvm
- Use the Node.js version specified in [.nvmrc](.nvmrc)

```
# use the Node.js version specified in .nvmrc
nvm use

# if not installed:
nvm install

# Verify the Node.js version:
node -v

# Verify npm version:
npm -v
```

### Recommended Browser Setup

- Chromium-based browsers (Chrome, Edge, Brave, etc.):
    - [Vue.js devtools](https://chromewebstore.google.com/detail/vuejs-devtools/nhdogjmejiglipccpnnnanhbledajbpd)
    - [Turn on Custom Object Formatter in Chrome DevTools](http://bit.ly/object-formatters)
- Firefox:
    - [Vue.js devtools](https://addons.mozilla.org/en-US/firefox/addon/vue-js-devtools/)
    - [Turn on Custom Object Formatter in Firefox DevTools](https://fxdx.dev/firefox-devtools-custom-object-formatters/)

### Type Support for `.vue` Imports in TS

TypeScript cannot handle type information for `.vue` imports by default, so we replace the `tsc` CLI with `vue-tsc` for
type checking. In editors, we need [Volar](https://marketplace.visualstudio.com/items?itemName=Vue.volar) to make the
TypeScript language service aware of `.vue` types.

### Install dependencies

```sh
npm install --save --save-exact
```

### Update dependencies

```sh
# check for outdated dependencies and update package.json if necessary (exact/pinned versions are preferred for better reproducibility and security)
npm outdated

# Update versions in package.json

# update dependencies to the latest version according to the version ranges specified in package.json
npm update --save --save-exact
```

### Compile and Hot-Reload for Development

```sh
npm run dev
```

### Regenerate the API types after a BFF change

`src/api/schema.d.ts` is **generated from the BFF's OpenAPI document and committed**. It is not produced by the build, and
nothing checks that it is current — so if you changed the BFF's public API (new endpoint, renamed or added response field,
changed type), regenerate it as part of the same change. Otherwise the frontend keeps type-checking against an API that no
longer exists.

The generator reads the document over HTTP from a **running BFF**:

```sh
# 1. Start the BFF first — from the repository root
./gradlew :events-bff:bootRun      # or: scripts/dev-env.sh up bff

# 2. Regenerate (from events-frontend/)
npm run generate:api

# 3. Review and follow the change through
git diff src/api/schema.d.ts
npm run type-check
```

Restart the BFF after editing a controller or DTO: with a stale BFF running, the command succeeds and quietly writes the
_old_ API. Never edit `schema.d.ts` by hand — the next run discards the edit. Friendly aliases for the generated schemas
live in [`src/api/types.ts`](src/api/types.ts); use those in views and composables.

### Format code

```sh
npm run format
```

Formatting is handled by [oxfmt](https://oxc.rs/). If you use IntelliJ IDEA, install the
[oxc plugin](https://plugins.jetbrains.com/plugin/27061-oxc) so the IDE formats code
identically to `npm run format` — without it, IntelliJ's built-in formatter disagrees with oxfmt.

### Type-Check, Compile and Minify for Production

```sh
npm run build
```

### Run Unit Tests with [Vitest](https://vitest.dev/)

```sh
npm run test:unit
```

### Run Unit Tests with Coverage

```sh
npm run test:unit:coverage
```

This prints a coverage summary to the console and generates a detailed HTML report in `coverage/index.html`.
Uses V8's native code coverage via `@vitest/coverage-v8`.

### Run End-to-End Tests with [Playwright](https://playwright.dev)

```sh
# Install browsers for the first run
npx playwright install

# When testing on CI, must build the project first
npm run build

# Runs the end-to-end tests
npm run test:e2e
# Runs the tests only on Chromium
npm run test:e2e -- --project=chromium
# Runs the tests of a specific file
npm run test:e2e -- tests/example.spec.ts
# Runs the tests in debug mode
npm run test:e2e -- --debug
```

### Run the accessibility sweep

```sh
# The axe/WCAG 2.1 AA sweep on its own, across all five browser projects
npm run test:a11y

# The fast local loop
npm run test:a11y -- --project=chromium
```

Powered by [axe-core](https://github.com/dequelabs/axe-core) through
[`@axe-core/playwright`](https://playwright.dev/docs/accessibility-testing). This is a _filter_ over the e2e suite, not a
separate check — `npm run test:e2e` already runs it, so CI is covered. It exists so markup work does not have to pay for
the whole suite. The static half of the same target is `eslint-plugin-vuejs-accessibility`, which runs inside
`npm run lint`. Neither may be silenced to make a build pass.

### Lint with [ESLint](https://eslint.org/)

```sh
npm run lint
```

### Add UI components (shadcn-vue)

```sh
# Add a component — it is generated into src/components/ui/<name>/ and owned by us (edit freely)
npx shadcn-vue@latest add button
npx shadcn-vue@latest add card dialog
```

The theme (colours, radius, typography, light/dark mode) is defined as CSS variables in
[`src/assets/main.css`](src/assets/main.css). Re-theme by editing those variables — see
[ADR-010](../docs/adr/ADR-010_FRONTEND_STYLING_FRAMEWORK.md).

#### Updating a component to a newer registry version

Components are copied into the repo and owned by us, so there is no automatic upgrade. To pull a newer
upstream version, use git as a safety net — `--overwrite` replaces the file wholesale and does **not** merge:

```sh
# 1. Check whether the registry version differs from ours
npx shadcn-vue@latest diff button

# 2. On a clean working tree, overwrite with the latest version
npx shadcn-vue@latest add button --overwrite

# 3. Review what changed (and what it clobbered), then reconcile
git diff src/components/ui/button
```

If you have customized the component, prefer hand-porting the change shown by `diff` rather than
overwriting. This applies only to `src/components/ui/**` — your own components are not registry-managed.

## Customize configuration

See [Vite Configuration Reference](https://vite.dev/config/).
