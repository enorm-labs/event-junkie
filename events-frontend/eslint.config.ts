import { globalIgnores } from 'eslint/config'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import pluginVue from 'eslint-plugin-vue'
import pluginVueA11y from 'eslint-plugin-vuejs-accessibility'
import pluginPlaywright from 'eslint-plugin-playwright'
import pluginVitest from '@vitest/eslint-plugin'
import pluginOxlint from 'eslint-plugin-oxlint'
import skipFormatting from 'eslint-config-prettier/flat'
import { commentDensity } from './eslint-rules/comment-density.ts'
import { commentSmell } from './eslint-rules/comment-smell.ts'
import { maxCommentLines } from './eslint-rules/max-comment-lines.ts'

// To allow more languages other than `ts` in `.vue` files, uncomment the following lines:
// import { configureVueProject } from '@vue/eslint-config-typescript'
// configureVueProject({ scriptLangs: ['ts', 'tsx'] })
// More info at https://github.com/vuejs/eslint-config-typescript/#advanced-setup

export default defineConfigWithVueTs(
  {
    name: 'app/files-to-lint',
    files: ['**/*.{vue,ts,mts,tsx}'],
  },

  globalIgnores(['**/dist/**', '**/dist-ssr/**', '**/coverage/**']),

  ...pluginVue.configs['flat/essential'],
  vueTsConfigs.recommended,

  // Accessibility lint — the static half of the WCAG 2.1 AA target
  // (docs/LEGAL.md §12). Catches missing form labels, bad `alt`, redundant
  // roles and click handlers on non-interactive elements at lint time; the axe sweep in
  // e2e/a11y.spec.ts covers what static analysis cannot see (contrast, focus order, live regions).
  // Do not disable a rule here to make a build pass — fix the markup, or raise it.
  ...pluginVueA11y.configs['flat/recommended'],

  {
    name: 'app/a11y-overrides',
    files: ['**/*.vue'],
    rules: {
      // `label-has-for` defaults to requiring nesting AND a for/id pair. Wrapping the control in
      // its <label> is implicit association — valid HTML, correctly announced by screen readers,
      // and what the checkbox filters in EventFilterBar.vue do. Requiring `some` rather than
      // `every` restores the actual WCAG bar (1.3.1 / 4.1.2): a label must be associated, not
      // associated twice. This relaxes an over-strict default; it does not permit unlabelled
      // controls, which the rule still catches.
      'vuejs-accessibility/label-has-for': [
        'error',
        { required: { some: ['nesting', 'id'] }, allowChildren: true },
      ],
    },
  },

  {
    ...pluginPlaywright.configs['flat/recommended'],
    files: ['e2e/**/*.{test,spec}.{js,ts,jsx,tsx}'],
  },

  {
    ...pluginVitest.configs.recommended,
    files: ['src/**/__tests__/*'],
  },

  {
    // shadcn-vue components are vendored (copied in, not authored by us) and use
    // single-word names by design (Button, Card, Dialog, ...).
    name: 'app/shadcn-ui-overrides',
    files: ['src/components/ui/**/*.vue'],
    rules: {
      'vue/multi-word-component-names': 'off',
    },
  },

  {
    // This repository's own rules — the frontend counterpart to the `:detekt-rules` Gradle module,
    // which carries the same cap for Kotlin. It lives on the ESLint side because oxlint is Rust and
    // cannot host a JS plugin at all.
    //
    // 15 rather than Kotlin's 25: the number comes from this tree's own distribution. Of 285 block
    // comments, none reached 25 lines and ten passed 15, so 25 would never fire here. See
    // .github/instructions/comments.instructions.md
    // `comment-density` and `comment-smell` are the counterparts to the detekt rules of the same
    // names. Density is per file, where a per-comment cap cannot see twenty reasonable comments
    // adding up to prose with code between it; `minCommentLines` keeps a short file that is one
    // declaration and its rationale out of it. Smell reports what AGENTS.md already forbids and
    // nothing failed on: a date, a markdown heading, a comment narrating its own history, a `TODO`.
    name: 'app/comment-length',
    files: ['**/*.{vue,ts,mts,tsx}'],
    // `schema.d.ts` is generated from the OpenAPI document; its comment count is nobody's to act on.
    ignores: ['src/api/schema.d.ts'],
    plugins: {
      'event-junkie': {
        rules: {
          'max-comment-lines': maxCommentLines,
          'comment-density': commentDensity,
          'comment-smell': commentSmell,
        },
      },
    },
    rules: {
      'event-junkie/max-comment-lines': ['error', { max: 15 }],
      'event-junkie/comment-density': ['error', { maxPercent: 70, minCommentLines: 25 }],
      'event-junkie/comment-smell': 'error',
    },
  },

  {
    // Where a date in a comment is a fact about the world rather than a changelog entry, and so is
    // exactly what the comment is for. Tests pin clocks and cite fixture provenance; the legal
    // module records when an address became real and when a DPA was concluded, which is the kind of
    // thing a supervisory authority asks about. `comment-density` stays on everywhere except the
    // legal module, which is compliance data with its rationale attached — the shape `EventsSchema`
    // has on the Kotlin side, only larger.
    name: 'app/comment-smell-exemptions',
    files: ['**/__tests__/**', 'e2e/**', 'src/lib/legal.ts', 'src/views/legal/**'],
    rules: { 'event-junkie/comment-smell': 'off' },
  },

  {
    name: 'app/comment-density-exemptions',
    files: ['src/lib/legal.ts'],
    rules: { 'event-junkie/comment-density': 'off' },
  },

  ...pluginOxlint.buildFromOxlintConfigFile('.oxlintrc.json'),

  skipFormatting,
)
