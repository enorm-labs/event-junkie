<!--
Keep this short. The PR title drives the release notes and the labels
(.github/workflows/label-pr.yml), so it must follow Conventional Commits.
-->

## What and why

<!-- One or two sentences. Link the issue if there is one. -->

## Checks

- [ ] `./gradlew clean build` passes (skip for docs-only or `events-frontend/`-only changes)
- [ ] Frontend: `npm run type-check`, `npm run lint`, `npm run test:unit`, `npm run test:e2e`

## Privacy & legal

<!--
See "Privacy & GDPR — re-check when infrastructure or features change" in AGENTS.md. The changes
that invalidate the privacy notice rarely look like privacy work — a hosting provider, an embedded
widget, an analytics snippet, or anything newly stored on the visitor's device.
-->

- [ ] This change does **not** affect data processing, third-party requests, or storage on the visitor's device — **or** it does, and the privacy notice and
      `docs/LEGAL.md` §7 are updated in this PR.

## Accessibility

<!-- Only relevant for frontend changes. See "Accessibility" in events-frontend/AGENTS.md. -->

- [ ] New interactive elements have accessible names; `npm run lint` and the axe sweep in
      `e2e/a11y.spec.ts` pass without rules being disabled.
