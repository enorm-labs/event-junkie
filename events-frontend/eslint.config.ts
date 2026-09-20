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

export default defineConfigWithVueTs(
  {
    name: 'app/files-to-lint',
    files: ['**/*.{vue,ts,mts,tsx}'],
  },

  globalIgnores(['**/dist/**', '**/dist-ssr/**', '**/coverage/**']),

  ...pluginVue.configs['flat/essential'],
  vueTsConfigs.recommended,

  // Accessibility lint, the static half of the WCAG 2.1 AA target (docs/LEGAL.md §12); the axe
  // sweep in e2e/a11y.spec.ts covers what static analysis cannot see. Do not disable a rule here
  // to make a build pass.
  ...pluginVueA11y.configs['flat/recommended'],

  {
    name: 'app/a11y-overrides',
    files: ['**/*.vue'],
    rules: {
      // `label-has-for` defaults to requiring nesting AND a for/id pair. Wrapping the control in its
      // <label> is valid, correctly announced, and what EventFilterBar.vue's checkboxes do; `some`
      // restores the WCAG bar (1.3.1 / 4.1.2), a label associated once. Unlabelled controls are still
      // caught.
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
    // shadcn-vue components are vendored and use single-word names by design (Button, Card, Dialog).
    name: 'app/shadcn-ui-overrides',
    files: ['src/components/ui/**/*.vue'],
    rules: {
      'vue/multi-word-component-names': 'off',
    },
  },

  {
    // This repository's own rules, the counterpart to `:detekt-rules`; on the ESLint side because
    // oxlint is Rust and cannot host a JS plugin. 15 rather than Kotlin's 25 from this tree's own
    // distribution: of 285 block comments none reached 25 and ten passed 15
    // (.github/instructions/comments.instructions.md). `comment-density` is per file, where a
    // per-comment cap cannot see twenty reasonable comments adding up; `minCommentLines` keeps a
    // short file that is one declaration and its rationale out of it. `comment-smell` reports a
    // date, a markdown heading, a comment narrating its own history, a `TODO`.
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
    // Where a date in a comment is a fact about the world: tests pin clocks and cite fixture
    // provenance, and the legal module records when an address became real and when a DPA was
    // concluded, which a supervisory authority asks about. `comment-density` stays on everywhere
    // but the legal module, compliance data with its rationale attached.
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
