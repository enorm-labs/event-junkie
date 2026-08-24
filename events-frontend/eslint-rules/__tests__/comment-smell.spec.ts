import { RuleTester } from 'eslint'
import { describe, it } from 'vitest'

import { commentSmell } from '../comment-smell.ts'

// RuleTester drives the test framework itself rather than being called from inside a test, so it
// has to be handed vitest's hooks.
RuleTester.describe = describe
RuleTester.it = it

const ruleTester = new RuleTester({
  languageOptions: { ecmaVersion: 2024, sourceType: 'module' },
})

ruleTester.run('comment-smell', commentSmell, {
  valid: [
    '/** Splits a headliner title into its co-billed acts. */\nconst a = 1\n',
    // A date inside backticks is a format example, not a changelog entry.
    '/** Handles both `2026-05-16T20:00` and `2026-05-16`. */\nconst a = 1\n',
    // …and so is one inside quotes.
    '// Format example: "astra:2026-06-12-the-adicts".\nconst a = 1\n',
    // The verb sense of "used to" is what a doc comment usually means.
    '/** @param baseUrl the URL it was fetched from, used to resolve the links. */\nconst a = 1\n',
    // A fenced block is sample data, not prose.
    '/**\n * Summary.\n *\n * ```ts\n * parse(x) // 2026-05-16\n * ```\n */\nconst a = 1\n',
  ],
  invalid: [
    {
      code: '/**\n * Summary.\n *\n * ## Why this exists\n */\nconst a = 1\n',
      errors: [{ messageId: 'heading' }],
    },
    {
      code: '// Measured on staging 2026-08-20, before the retry landed.\nconst a = 1\n',
      errors: [{ messageId: 'date' }],
    },
    {
      code: '// The uploads that used to sit here now run after the build.\nconst a = 1\n',
      errors: [{ messageId: 'history' }],
    },
    { code: '// TODO: handle the empty case.\nconst a = 1\n', errors: [{ messageId: 'todo' }] },
    // Two dates in one comment are one thing to fix, so they report once.
    {
      code: '/**\n * Measured 2026-08-20.\n * Re-measured 2026-08-21.\n */\nconst a = 1\n',
      errors: [{ messageId: 'date' }],
    },
  ],
})
