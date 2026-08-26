# Compact Comments

Find comments that cost more than they earn and pay them down — by deleting, renaming or extracting first, and only rewriting what genuinely has to stay. The
burn-down counterpart to the guards in #713: those stop the volume rising, this is what brings it down.

## Important

- **Deletion is the default; rewriting is the exception.** Most long comments are not badly worded, they are unnecessary. Working through the buckets in order
  (below) is what keeps the effort on removal rather than on polishing prose that should not exist.
- **Never lose an accepted limitation.** Venue sub-package KDoc is the designated home for them (#393, #714) — a field the venue never publishes, a trade-off a
  parser makes deliberately. That prose is what stops the same non-defect being re-reported by every `/data-quality-audit`. **Shorten how it is said; never
  delete what it says.** If a fact has to leave a comment, it moves somewhere reviewable first — a test name, an issue, an ADR.
- **Re-wrapping is not compaction.** Re-flowing a comment to a wider column drops its line count without removing a word. The caps count lines, so this passes
  the lint and changes nothing — it is gaming the metric while claiming to fix it. Cut words, not line breaks.
- **Never raise a baseline to go green.** `comment-baseline.txt` and `comment-lint-baseline.txt` move down. A genuinely new file adding genuinely new comments is
  the one exception, and it is argued in the PR, not absorbed quietly.
- This skill edits code. Run [`/verify`](verify.prompt.md) before handing back, and never leave the tree red.

## Running unattended

[`agent-comments.yml`](../workflows/agent-comments.yml) invokes this prompt as `/compact-comments --unattended` from a runner. The buckets do not change; what
changes is which of them an unwatched run is allowed to reach.

- **DELETE, RENAME and EXTRACT only.** Those three are checkable by a reviewer in seconds — the comment is gone, the name is better, the function is named. They
  are also the buckets where deletion is the right answer, which is the point of the ordering.
- **RELOCATE is reported, never applied.** Moving a fact to a test, an issue or an ADR is a judgement about where it belongs, and getting it wrong loses the
  fact rather than moving it. List the candidates with their destinations and let a human place them.
- **KEEP is reported, never rewritten.** This is the rule that matters most here. A rewrite of load-bearing reasoning reads as an improvement whatever it
  deleted, and nobody re-reads a comment that still looks fine. The `asd-ste100` pass on prose that has to stay is a human's job.
- **Never touch a scraper's venue KDoc**, whatever its density. It is the designated home for accepted limitations (#393, #714), it is the densest prose in the
  repository by construction, and it is the single most attractive target for something optimising for line count.
- **The baseline only moves down.** If the run cannot lower a number honestly, it leaves the number alone. An unattended run must never argue for a raise,
  because the argument is the part a human makes in the pull request.
- **Rank the whole tree, then read at most twenty files, then change at most twelve.** Those are three different numbers and only the last one is about the
  diff. `--all` names hundreds of files, and reading all of them is how a run exhausts itself before it writes anything — rank first from
  `comment-density.sh report`, which needs no file opened, and open only the top of that list. Report what was left, and let the next run take it: a sweep that
  converges over four runs is worth more than one that plans a fifth and delivers none.
- **`--dry-run`** on top of it opens no pull request and writes the report to the job summary. Use it first, and after any change to this section.

The proof obligation is unchanged and is what makes the workload safe at all: `scripts/comment-density.sh check` and `scripts/comment-lint.sh check` measure the
result mechanically, and a comment-only change that turns a test red went further than intended.

**Your final message is the report, and there is no second turn.** The run ends the moment you stop calling tools, so a closing line like _"I'll compile the
report once the checks finish"_ ends it with that sentence as the whole deliverable — and the job still reports success. There is nobody to hand off to and
nothing to wait for: no reviewer reads the transcript, no follow-up prompt arrives, and any work you plan but do not do in this turn is simply lost. Finish the
work, then write the Output section below as your last message. This has already happened once, on a `--all` sweep that ended waiting for classification agents
it had no tool to spawn.

## Usage

```
/compact-comments              # the current diff — the default
/compact-comments --all        # every tracked file, in density order
/compact-comments --worst N    # the N densest files in the repository
/compact-comments <path>       # one file or directory
```

## Steps

### 1. Find the targets

```bash
scripts/comment-density.sh report --top 20     # where the volume actually is
scripts/comment-lint.sh report                 # every rule violation, by type
./gradlew detekt --console=plain -q            # LongComment / CommentDensity / CommentSmell
```

Default to the current diff (`git --no-pager diff --stat main...HEAD`). With `--worst N`, rank by comment lines × ratio rather than by ratio alone: a 90% file
with 20 comment lines is a declaration with its rationale attached, and there is nothing to win there.

**`--worst N` and `--all` do not find the same thing, and the difference is why both exist.** Ranking by comment lines × ratio selects for files that are dense
_deliberately_ — the venue KDoc, the Gradle traps, the metrics contracts — which is where the least is removable. Boilerplate is the opposite shape: a few lines
each, spread across many files, ranking nowhere. `--all` walks every tracked file so that population is reachable at all.

**Read the whole comment before touching it.** The single most common mistake is compressing a paragraph that should have been deleted outright.

### 2. Classify every block into exactly one bucket

| Bucket       | The comment…                                                            | What to do                                                                       |
| ------------ | ----------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| **DELETE**   | restates what the code plainly does, or the signature already says it   | Remove it. `@param foo the foo`, "increments the counter", purity boilerplate.   |
| **RENAME**   | exists to explain a name                                                | Fix the name; the comment goes. A rename cannot go stale.                        |
| **EXTRACT**  | explains a block of code inside a function                              | Extract a named function; the name carries what the comment said.                |
| **RELOCATE** | states a constraint, a promise or a fact that belongs somewhere checked | Move it: a test name, an issue, an ADR, `docs/`. Leave a pointer, not a summary. |
| **KEEP**     | records a genuine _why_ nothing else can carry                          | Compress the words. This is the only bucket that gets rewritten.                 |

**Apply the buckets in that order.** Every block resolved earlier is one you never have to word well.

### 3. What each bucket looks like here

- **DELETE** — the boilerplate #393 removed from 106 files and that copying a scraper puts straight back: `@param document the parsed Jsoup document`, "performs
  no I/O" (stated once on `AbstractTwoPageWebsiteImporter`), the fixture-and-mock test setup.
- **RELOCATE, and check first** — a fenced `Example:` block in KDoc is usually a **second copy of an assertion that already exists**. Grep the example's own
  strings against the test tree before deciding; if the tests hold them, the block goes and the KDoc says so in a clause. Both `ArtistNameMapping` and
  `MorphineFieldMapping` carried a dozen such lines each.
- **KEEP, and compress** — the reasoning that survives is the trade-off, the constraint from outside, the non-obvious failure the shape avoids. Invoke the
  `asd-ste100` skill for the rewrite: ≤20-word sentences, active voice, present tense, one word per concept, ≤3 sentences, no rhetorical build-up.

**The four things that make a comment long here**, and what each one becomes:

1. A **markdown heading** (`## Why this exists`) — the content is a document in the wrong file. Move it to `docs/`, an ADR or the issue; leave a pointer.
2. A **date or an incident narrative** (`Measured on staging 2026-08-20`, `failed at 11:54 and was next attempted…`) — git blame, the PR and the issue hold it.
   Keep the conclusion, drop the forensics.
3. **History** (`This used to ask "does it contain -SNAPSHOT?"`) — rewrite in the present tense. If the old approach is a live trap somebody will re-introduce,
   name the trap in one sentence rather than telling its story.
4. An **argument reconstructed from the PR thread** — keep what constrains _this_ code, and reference the rest.

### 4. What is not a smell, and must not be "fixed"

The lint already knows these; a human sweep is where they get broken.

- **A date inside backticks, quotes or a fenced block is data.** This tree documents its parsers with `"2026-05-16T20:00"` far more often than it dates a
  decision — of 57 date-shaped strings in Kotlin main, 4 were changelog entries.
- **"used to" is nearly always the verb** — "used to resolve the links", not "it used to resolve the links". 78 hits, 1 of them narration.
- **A pinned clock in a test** ("the clock is pinned to 2026-07-01") is a fact about a fixture, and a **legal date** (when an address became real, when a DPA was
  concluded) is a compliance record. Both are exempt from `CommentSmell` for that reason.
- **A long comment recording a deliberate trade-off is not boilerplate**, however long it is. Ask for it in fewer words; the reasoning stays.

### 5. Apply, then prove it

Work file by file and re-run the checks after each one — the caps are per comment, so a single edit can move a file from four findings to none.

```bash
./gradlew detekt --console=plain -q
cd events-frontend && npx eslint .
scripts/comment-lint.sh check
scripts/comment-density.sh check
```

When a count has genuinely dropped, commit the lower ceiling in the same change:

```bash
git add -N <any new file>          # git ls-files cannot see an untracked one
scripts/comment-density.sh update-baseline
scripts/comment-lint.sh update-baseline
```

Then run [`/verify`](verify.prompt.md) in full. **A comment-only change must not alter behaviour**, so a failing test means an edit went further than intended —
read it rather than working around it.

### 6. When a comment genuinely cannot fit

Suppress it, with a reason, on the declaration:

- `@Suppress("LongComment")` for Kotlin, on the declaration the block documents.
- `// eslint-disable-next-line event-junkie/max-comment-lines — <reason>` for TS and Vue.
- `# comment-lint: allow <reason>` on the line above the block, for Terraform, shell, YAML and Python — or `# comment-lint: allow-file <reason>` anywhere in
  the file to accept its density, for a declarative file whose every line needs a reason. A bare directive is itself a violation.

A suppression is an explicit decision a reviewer can see. **Two or three across a sweep is a judgement call; a dozen means the sweep gave up** — say so plainly
rather than shipping the count.

## Output

Report per bucket, not per file, because the buckets are the point:

```
DELETE    31 lines   restated signatures, purity boilerplate
RELOCATE  22 lines   4 Example: blocks already asserted in tests
KEEP      18 lines   compressed, reasoning intact
RENAME     0
EXTRACT    0
─────────────────────
          71 lines removed across 9 files

events-importer  20772 → 20516   (-256)
detekt           28 findings → 0
suppressions     2 (insel, peteredel — accepted limitations, #714)
```

Then state what `/verify` did, and name anything you deliberately left: a comment you judged load-bearing, a file you did not reach, a suppression you added and
why. **A sweep that quietly skipped the hard files reads exactly like one that finished.**
