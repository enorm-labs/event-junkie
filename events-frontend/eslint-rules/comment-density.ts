import type { Rule } from 'eslint'
import type { Comment } from 'estree'

/**
 * Reports a file that is more comment than code — the frontend half of detekt's `CommentDensity`
 * (#713).
 *
 * `max-comment-lines` caps one comment; twenty individually reasonable ones still make a file that
 * reads as prose with code between it, and no per-comment cap sees that. This is the per-file
 * backstop; `scripts/comment-density.sh report` shows the same totals per area but gates nothing.
 *
 * A line carrying both code and a trailing comment counts as code, as `cloc` does. `minCommentLines`
 * keeps a short file with one explanatory paragraph out of it — that ratio is meaningless.
 */
const DEFAULT_MAX_PERCENT = 70
const DEFAULT_MIN_COMMENT_LINES = 25

export const commentDensity: Rule.RuleModule = {
  meta: {
    type: 'suggestion',
    docs: { description: 'Cap the share of a file that may be comment.' },
    schema: [
      {
        type: 'object',
        properties: {
          maxPercent: { type: 'integer', minimum: 1, maximum: 100 },
          minCommentLines: { type: 'integer', minimum: 1 },
        },
        additionalProperties: false,
      },
    ],
    messages: {
      tooDense:
        'This file is {{percent}}% comment, more than the maximum of {{max}}%. Say it in fewer words, or let a name say it instead.',
    },
  },

  create(context) {
    const options = context.options[0] as { maxPercent?: number; minCommentLines?: number } | undefined
    const maxPercent = options?.maxPercent ?? DEFAULT_MAX_PERCENT
    const minCommentLines = options?.minCommentLines ?? DEFAULT_MIN_COMMENT_LINES

    return {
      Program(node) {
        const lines = context.sourceCode.lines
        const commentLines = new Set<number>()

        for (const comment of context.sourceCode.getAllComments() as Comment[]) {
          const { start, end } = comment.loc!
          // A comment that trails code leaves that line to the code, so it starts on the next one.
          const trailsCode = lines[start.line - 1].slice(0, start.column).trim() !== ''
          for (let line = trailsCode ? start.line + 1 : start.line; line <= end.line; line++) {
            commentLines.add(line)
          }
        }

        if (commentLines.size < minCommentLines) return

        const nonBlank = lines.filter((line) => line.trim() !== '').length
        if (nonBlank === 0) return

        const percent = Math.floor((commentLines.size * 100) / nonBlank)
        if (percent > maxPercent) {
          context.report({
            node,
            messageId: 'tooDense',
            data: { percent: String(percent), max: String(maxPercent) },
          })
        }
      },
    }
  },
}
