<!--
Keep this short. The PR title drives the release notes and the labels
(.github/workflows/label-pr.yml), so it must follow Conventional Commits.
-->

## What and why

<!-- One or two sentences. If this finishes an issue, put `Closes #<n>` on its own line here. -->

## Checks

<!-- The full sequence, including what to run for infra/, deploy/ and dependency changes, is
     .github/prompts/verify.prompt.md. Tick what you ran; leave what the change did not touch. -->

- [ ] Backend: `./gradlew ktlintCheck detekt build koverLog`
- [ ] Frontend: `npm run type-check`, `npm run lint`, `npm run test:unit -- --run`, `npm run test:e2e -- --project=chromium`
- [ ] `scripts/comment-lint.sh check`, plus `scripts/format-markdown.sh check` and `scripts/ste-lint.sh check` if a `.md` file changed
- [ ] Importer change touching shared normalization: a `--full` re-seed and a diff, and the outcome stated below — **or** the change is local to one
      scraper and only affects future imports

## Privacy & legal

<!--
See "Privacy & GDPR — re-check when infrastructure or features change" in AGENTS.md. The changes
that invalidate the privacy notice rarely look like privacy work — a hosting provider, an embedded
widget, an analytics snippet, or anything newly stored on the visitor's device.
-->

- [ ] This change does **not** affect data processing, third-party requests, or storage on the visitor's device — **or** it does, and the privacy notice
      (both languages) and `docs/LEGAL.md` §7 are updated in this PR.

## Accessibility

<!-- Only relevant for frontend changes. See "Accessibility" in events-frontend/AGENTS.md. -->

- [ ] New interactive elements have accessible names; `npm run lint` and the axe sweep in
      `e2e/a11y.spec.ts` pass without rules being disabled.
