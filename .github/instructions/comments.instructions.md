---
applyTo: "**/*.kt,**/*.kts,**/*.ts,**/*.vue,**/*.js,**/*.tf,**/*.sh,**/*.py,**/*.yaml,**/*.yml"
paths:
    - "**/*.kt"
    - "**/*.kts"
    - "**/*.ts"
    - "**/*.vue"
    - "**/*.js"
    - "**/*.tf"
    - "**/*.sh"
    - "**/*.py"
    - "**/*.yaml"
    - "**/*.yml"
---

# Comments, in Every Language

Lint enforces the mechanical half of this; the rest is review.

- **Comments: few, short, about _why_, and in the present tense — in every language, not just Kotlin.** A comment is prose that has to be maintained like the
  code it sits on, so every line has to earn its keep. Default to one or two sentences. **Before writing one, try to make it unnecessary**: a clearer name or an
  extracted function says the same thing and cannot go stale. See
  [best practices for writing code comments](https://stackoverflow.blog/2021/12/23/best-practices-for-writing-code-comments/) and #713.
    - **Explain _why_, not _what_** — the code already says what it does. A comment earns its place by recording what a reader cannot recover from the code: a
      trade-off, a constraint imposed from outside, a non-obvious failure this shape avoids. **Self-explanatory code needs no comment at all.**
    - **Rewrite, never append.** When behaviour changes, edit the comment to describe the code as it stands. "It used to…", "since #540 it now…", "note: this
      also handles…" are the same defect: a comment narrating a journey instead of a state.
    - **No history, no dates, no changelog.** git blame, the PR and the issue already hold when something changed and why. Drop "as of 2026-08-18", "previously"
      and re-tellings of a review thread. **One exception**: an abandoned approach that is a live trap — one sentence naming the trap, not the story of it.
    - **A KDoc example is not a test, and writing one suppresses the instinct to go and write the test.** Seven public helpers under
      `events-importer/.../scraper/` were documented with worked `Example:` blocks and asserted by nothing (#726), while reporting 100% line coverage because
      venue fixtures call them. Two of those blocks had already drifted from the assertion they duplicated. If a case is worth showing it is worth asserting:
      put it in the test, and name the suite if a reader needs the examples.
    - **A fact another tool has to act on belongs in a record, not in prose.** Leaving what a venue's site does not publish in scraper KDoc makes
      `/data-quality-audit` parse free text across 213 files to tell an accepted trade-off from a defect, which is silently wrong in both directions. It is a
      `VenueLimitations` declaration per importer instead, rendered to `docs/data-quality/ACCEPTED_LIMITATIONS.md` and asserted by a test (#715). The KDoc keeps
      the reasoning: which selector, which trap, what the parser does instead. A comment is for a human reading this code; a record is for anything else.
    - **An issue or ADR reference is a pointer, not a summary.** Write `see #540` and stop. Duplicating AGENTS.md or an ADR into a comment creates a second copy
      that drifts; keep in the code only what constrains that specific code.
    - **No document structure inside a comment.** Markdown headings (`## Why this exists`), bold section titles and multi-paragraph argument mean the content is a
      document in the wrong file. Put it in `docs/`, an ADR or the issue, and leave a pointer.
    - **Don't restate the signature.** Types, nullability, annotations and `@param foo the foo` boilerplate are noise. Document a parameter only for a constraint
      the type cannot express — units, a range, an accepted format.
    - **Lead with one summary sentence.** KDoc renders the first sentence alone in tooling and IDE popups, so it has to stand by itself.
    - **Assert behaviour in a test, don't promise it in a comment.** A comment claiming "callers must call `close()`" or "returns at most 50" goes stale in
      silence; a test fails loudly. Prefer the test, and let the comment carry the reason the rule exists.
    - **No commented-out code and no `TODO`s.** Deleted code lives in git; work worth remembering is an issue (see _The Backlog — GitHub Issues_).
    - **How to write the sentences.** A six-rule subset of [ASD-STE100](https://www.asd-ste100.org/), chosen because each one is checkable: one topic per
      sentence, **20 words maximum** · active voice, present tense · **one word, one meaning** — do not rename a thing mid-comment for variety · **three sentences
      maximum** per comment · direct statements, not narrative · no rhetorical build-up. The `asd-ste100` skill applies these to a block that has to stay.
      It is **vendored into `.claude/skills/asd-ste100/`** (MIT, third-party) rather than left to each contributor's global install, because
      `/compact-comments` invokes it by name, and a skill present on one machine makes that instruction silently do nothing everywhere else. It is kept
      byte-identical to upstream: `VENDORED.md` beside it records the commit and the update command, and `.oxfmtrc.json` keeps the formatter off it.
    - **[`/compact-comments`](../../.github/prompts/compact-comments.prompt.md) is how the backlog comes down.** It classifies each block DELETE → RENAME → EXTRACT →
      RELOCATE → KEEP and applies them in that order, so deletion is the default and rewriting the exception. Reach for it before hand-editing a dense file.
    - **Volume is measured, not capped.** `scripts/comment-density.sh report --top 20` says where the comment lines are, and that is all it does — it gates
      nothing. The per-area ceiling it used to carry was removed: a budget on the count of comment lines fails the build for a number rather than for a comment
      anybody would object to, so it charged the same price for deleting a stale paragraph and for adding a load-bearing one, and the fix it asked for was
      always "write less here", never "write better here". The rules below are what is enforced, because each one names a defect in the comment itself.
    - **Terraform, shell, YAML and Python are linted by `scripts/comment-lint.sh`.** It applies the block cap, a per-file density cap and the rules above that
      are mechanical — a markdown heading inside a comment, a date literal, a comment narrating a change — and `check` fails on **any** violation, with no
      baseline to absorb one. Silence one block with `# comment-lint: allow <reason>` on the line above it, or a whole file's density with
      `# comment-lint: allow-file <reason>`; the reason is not optional either way and a bare directive is itself a violation.
    - **The density budget is 70% for code and 55% for the declarative formats** (#721), which is the one place the three implementations differ on purpose.
      The argument for parity is real — in Kotlin one comment explains a twenty-line function, while in HCL and YAML one comment explains one assignment, so
      the same reasoning lands at a higher ratio — but 70 was measured to flag two `.tf`/`.yaml` files where 55 flags seventeen, and raising a cap to clear
      findings is what #713 rejected when it threw away a working KDoc rewrapper. The floor differs for the same reason: 21 comment lines rather than 25.
      A declarative file that genuinely cannot fit says so with `allow-file` and a reason, which a reviewer sees; a looser number is not.
    - **Per-comment caps are enforced by lint.** `LongComment` (a custom detekt rule in `:detekt-rules`) caps a Kotlin comment at 25 lines and counts a run of
      `//` lines as one comment; `event-junkie/max-comment-lines` (a local ESLint rule in `events-frontend/eslint-rules/`) caps TS and Vue at 15, measured from
      that tree. Over the cap is `@Suppress(…)` or `// eslint-disable-next-line` **with a reason** — an explicit decision a reviewer can see, never a threshold
      raised until nothing fires.
    - **`CommentDensity` and `CommentSmell` enforce the rest** (#713). Density caps a file at 70% comment once it carries 25 comment lines; below that floor a
      file is a declaration with its rationale attached, not prose with code between it. Smell reports a date, a markdown heading, a comment narrating its own
      history, or a `TODO` — all of it already policy, none of it previously enforceable. **A date inside backticks, quotes or a fenced block is left alone**:
      this tree documents its parsers with `"2026-05-16T20:00"` far more often than it dates a decision, and a rule that cannot tell the two apart gets switched
      off. Test sources are excluded for the same reason — a pinned clock is a fact about a fixture. **"used to" preceded by a form of _be_ is the passive verb,
      not a narration** ("nothing here can be used to push"), and is not flagged. `events-frontend/eslint-rules/` carries both counterparts.
    - **Adding or editing a rule in `:detekt-rules` needs `./gradlew --stop` before it will run.** The daemon caches the plugin classloader, so a new rule is
      silently absent and an edited one silently keeps its old behaviour — detekt passes, reports the previous verdict, and nothing says why. It is not a
      config error and no amount of `--rerun-tasks` clears it.
    - **A block's length is its lines that carry something.** Blank `*` and `#` separators between paragraphs do not count, in `LongComment` (#741) and in
      `comment-lint.sh` (#750) alike: charging a comment for its paragraph breaks made the same words cheaper written as one wall of text, which is a rule
      rewarding the worse of two formattings. Delimiters, indentation and `@see` lines still count, so 25 is the whole block a reader scrolls past minus the
      breaks that make it scrollable. **Density is measured differently, deliberately** — all three implementations count a blank comment line in both halves
      of the ratio, so paragraphing does not move it.
    - **The one place length is welcome is a deliberate trade-off — and even there, compress the words, not the reasoning.** Venue scraper KDoc carries the
      shape of a hand-authored source: the markup sample, the trap, the counterexample that made a rule necessary. That prose is load-bearing and a
      `@Suppress("LongComment")` with a reason is the right answer for it. What the venue does not publish is _not_ prose any more — it is a
      `VenueLimitations` record (#715). Shorten how it is said; never delete what it says. See the _Where a finding goes_ table in
      [AGENTS.md](../../AGENTS.md) § The Backlog.
