import { RuleTester } from 'eslint'
import { describe, it } from 'vitest'

import { maxCommentLines } from '../max-comment-lines.ts'

// RuleTester drives the test framework itself rather than being called from inside a test, so it
// has to be handed vitest's hooks.
RuleTester.describe = describe
RuleTester.it = it

const ruleTester = new RuleTester({
  languageOptions: { ecmaVersion: 2024, sourceType: 'module' },
})

const tooLong = [{ messageId: 'tooLong' }]

ruleTester.run('max-comment-lines', maxCommentLines, {
  valid: [
    { code: '/**\n * One.\n * Two.\n */\nconst a = 1\n', options: [{ max: 4 }] },
    { code: '// One.\n// Two.\n\n// Three.\n// Four.\nconst a = 1\n', options: [{ max: 2 }] },
    // A trailing comment does not join the run below it: it belongs to its own line of code.
    { code: 'const a = 1 // One.\n// Two.\nconst b = 2\n', options: [{ max: 1 }] },
  ],
  invalid: [
    { code: '/**\n * One.\n * Two.\n */\nconst a = 1\n', options: [{ max: 3 }], errors: tooLong },
    // An unbroken run of `//` lines counts as one comment.
    { code: '// One.\n// Two.\n// Three.\nconst a = 1\n', options: [{ max: 2 }], errors: tooLong },
    { code: '/*\n One.\n Two.\n*/\nconst a = 1\n', options: [{ max: 3 }], errors: tooLong },
  ],
})
