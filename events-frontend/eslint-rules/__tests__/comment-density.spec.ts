import { RuleTester } from 'eslint'
import { describe, it } from 'vitest'

import { commentDensity } from '../comment-density.ts'

RuleTester.describe = describe
RuleTester.it = it

const ruleTester = new RuleTester({
  languageOptions: { ecmaVersion: 2024, sourceType: 'module' },
})

const tooDense = [{ messageId: 'tooDense' }]
const options = [{ maxPercent: 50, minCommentLines: 3 }]

ruleTester.run('comment-density', commentDensity, {
  valid: [
    { code: '// One.\n// Two.\n// Three.\nconst a = 1\nconst b = 2\nconst c = 3\n', options },
    // Too few comment lines for the ratio to mean anything.
    { code: '// One.\nconst a = 1\n', options },
    // A trailing comment counts as code, the way cloc does.
    {
      code: '// One.\n// Two.\n// Three.\nconst a = 1 // t\nconst b = 2 // t\nconst c = 3 // t\n',
      options,
    },
    // Blank lines do not count towards the total.
    {
      code: '// One.\n// Two.\n// Three.\n\nconst a = 1\n\nconst b = 2\n\nconst c = 3\n',
      options,
    },
  ],
  invalid: [
    { code: '// One.\n// Two.\n// Three.\n// Four.\nconst a = 1\n', options, errors: tooDense },
    {
      code: '/**\n * One.\n * Two.\n * Three.\n * Four.\n */\nconst a = 1\n',
      options,
      errors: tooDense,
    },
  ],
})
