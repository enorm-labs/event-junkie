import type { Rule } from 'eslint'
import type { Comment } from 'estree'

/**
 * Caps how many lines a single comment may span — the frontend half of the rule detekt's
 * `LongComment` enforces on the Kotlin tree (#573).
 *
 * A run of `//` lines with no blank line between them counts as one comment, because that is how it
 * reads. The cap is a smell detector, not a truth: what makes a comment long is nearly always
 * restated code or a history of how it got this way. See .github/instructions/comments.instructions.md.
 *
 * `// eslint-disable-next-line event-junkie/max-comment-lines` is the escape hatch, with a reason —
 * an explicit decision rather than a cap raised until nothing fires.
 */
const DEFAULT_MAX = 15

/** Groups the file's comments: one entry per block comment, one per unbroken run of `//` lines. */
function commentBlocks(comments: Comment[], startsLine: (comment: Comment) => boolean): Comment[][] {
  const blocks: Comment[][] = []
  for (const comment of comments) {
    const current = blocks.at(-1)
    const previous = current?.at(-1)
    const continues =
      previous !== undefined &&
      previous.type === 'Line' &&
      comment.type === 'Line' &&
      startsLine(previous) &&
      startsLine(comment) &&
      comment.loc?.start.line === previous.loc!.end.line + 1
    if (continues) current!.push(comment)
    else blocks.push([comment])
  }
  return blocks
}

export const maxCommentLines: Rule.RuleModule = {
  meta: {
    type: 'suggestion',
    docs: { description: 'Cap how many lines a single comment may span.' },
    schema: [
      {
        type: 'object',
        properties: { max: { type: 'integer', minimum: 1 } },
        additionalProperties: false,
      },
    ],
    messages: {
      tooLong:
        'This comment spans {{lines}} lines, more than the maximum of {{max}}. Keep the reason, drop the rest.',
    },
  },

  create(context) {
    const max = (context.options[0] as { max?: number } | undefined)?.max ?? DEFAULT_MAX
    const startsLine = (comment: Comment) =>
      context.sourceCode.lines[comment.loc!.start.line - 1]
        .slice(0, comment.loc!.start.column)
        .trim() === ''

    return {
      Program() {
        for (const block of commentBlocks(context.sourceCode.getAllComments(), startsLine)) {
          const start = block[0].loc!.start
          const end = block.at(-1)!.loc!.end
          const lines = end.line - start.line + 1
          if (lines > max) {
            context.report({
              loc: { start, end },
              messageId: 'tooLong',
              data: { lines: String(lines), max: String(max) },
            })
          }
        }
      },
    }
  },
}
