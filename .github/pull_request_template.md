<!--
Keep this short. The PR title drives the release notes and the labels
(.github/workflows/label-pr.yml), so it must follow Conventional Commits.
Delete every section below that does not apply; keep the others in this order.
-->

## What and why

<!-- One or two sentences, or a short list for a larger change. If this finishes an issue, put `Closes #<n>` on its own line here. -->

## Screenshots

<!-- Delete this section unless a visitor can see the change. Then add before and after; `gh pr create --attach` uploads them. -->

## After deploy

<!-- Delete this section unless something has to happen once this runs on a cluster: an empty one fails label-pr.yml.
     Each step is an unticked line, `- [ ] <staging|production|both>: <force-import slugs|check what passes|run scripts/… command>`,
     for example `- [ ] both: force-import <slug>`. label-pr.yml then adds the `after-deploy` label, and `/post-release` runs the steps
     after the next deployment (.github/prompts/post-release.prompt.md). -->

## Checks

<!-- The full sequence, including what to run for infra/, deploy/ and dependency changes, is
     .github/prompts/verify.prompt.md (`/verify` runs it). Tick what you ran and delete what the change did not touch,
     or paste the `/verify` report instead of the list. -->

- [ ] Backend: `./gradlew ktlintCheck detekt detektMain detektTest build koverLog -PwarningsAsErrors`
- [ ] Frontend: `npm run type-check`, `npm run lint`, `npm run test:unit -- --run`, `npm run test:e2e -- --project=chromium`
- [ ] `scripts/comment-lint.sh check`; `scripts/format-markdown.sh check` if a `.md` file changed, and `scripts/ste-lint.sh check` if one under `docs/` did
- [ ] Importer change touching shared normalization: a `--full` re-seed and a diff (`/importer-smoke <slug> --full`), with the outcome stated above —
      **or** the change is local to one scraper and only affects future imports

## Privacy & legal

<!--
See "Privacy & GDPR — re-check when infrastructure or features change" in AGENTS.md. The changes
that invalidate the privacy notice rarely look like privacy work — a hosting provider, an embedded
widget, an analytics snippet, or anything newly stored on the visitor's device.
-->

- [ ] This change does **not** affect data processing, third-party requests, or storage on the visitor's device — **or** it does, this description says so,
      and the privacy notice (both languages) and `docs/LEGAL.md` §7 are updated in this PR.

## Contributor Licence Agreement

<!-- For a contribution from a fork. The maintainer's own pull requests, and an agent's working for the maintainer, delete this section. -->

- [ ] I agree to the [Contributor Licence Agreement](https://github.com/enorm-labs/event-junkie/blob/main/CLA.md) for this contribution.

## Accessibility

<!-- Only relevant for frontend changes; delete it otherwise. See "Accessibility" in .github/instructions/vue.instructions.md. -->

- [ ] New interactive elements have accessible names; `npm run lint` and the axe sweep in
      `events-frontend/e2e/a11y.spec.ts` pass without rules being disabled.
