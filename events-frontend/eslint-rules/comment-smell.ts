import type { Rule } from 'eslint'
import type { Comment } from 'estree'

/**
 * Reports the things .github/instructions/comments.instructions.md already forbids, now that a lint run can see them: a date,
 * a markdown heading, a comment narrating its own history, and a `TODO`. The frontend half of
 * detekt's `CommentSmell` (#713).
 *
 * A date inside backticks or quotes is data, not a changelog, and is left alone — a format example
 * is far more common here than a dated decision, and a rule that cannot tell them apart gets
 * switched off. Only past-tense narration counts as history: "used to" is usually the verb ("used
 * to resolve the links"), so a subject pronoun has to precede it.
 */
const HEADING = /^#{2,6}\s/
const CODE_SPAN = /`[^`]*`/g
const QUOTED = /"[^"]*"|'[^']*'/g
const FENCE = /^```/

const PROSE_SMELLS = [
  { pattern: /\b(TODO|FIXME)\b/, messageId: 'todo' },
  { pattern: /\d{4}-\d{2}-\d{2}/, messageId: 'date' },
  {
    pattern: /\b(it|this|that|they|we|which|these|those)\s+used\s+to\b|\bpreviously[,.]|\bas of \d|\bnowadays\b/i,
    messageId: 'history',
  },
] as const

/** Strips the comment syntax a line carries, so only the prose is matched. */
function textOf(raw: string): string {
  return raw.trim().replace(/^\/\*\*?/, '').replace(/^\/\//, '').replace(/\*\/$/, '').trim().replace(/^\*/, '').trim()
}

export const commentSmell: Rule.RuleModule = {
  meta: {
    type: 'suggestion',
    docs: { description: 'Report a comment holding a date, a heading, its own history, or a TODO.' },
    schema: [],
    messages: {
      heading:
        'A markdown heading in a comment means the content is a document in the wrong file. Put it in docs/, an ADR or the issue, and leave a pointer.',
      date: 'A date belongs in git blame, the PR or the issue, not in a comment describing the code as it stands.',
      history:
        'This comment narrates a change rather than describing the code as it stands. Rewrite it in the present tense.',
      todo: 'A TODO rots in silence. File it as an issue and reference the number.',
    },
  },

  create(context) {
    return {
      Program() {
        for (const comment of context.sourceCode.getAllComments() as Comment[]) {
          // One report per smell per comment: three dates in one paragraph are one thing to fix.
          const seen = new Set<string>()
          let inFence = false

          for (const raw of comment.value.split('\n')) {
            const line = textOf(raw)
            if (FENCE.test(line)) {
              inFence = !inFence
              continue
            }
            if (inFence || line === '') continue

            if (HEADING.test(line)) seen.add('heading')
            const prose = line.replace(CODE_SPAN, ' ').replace(QUOTED, ' ')
            const smell = PROSE_SMELLS.find(({ pattern }) => pattern.test(prose))
            if (smell) seen.add(smell.messageId)
          }

          for (const messageId of seen) {
            context.report({ loc: { start: comment.loc!.start, end: comment.loc!.end }, messageId })
          }
        }
      },
    }
  },
}
