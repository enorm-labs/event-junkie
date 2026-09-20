# Compact Comments

Find comments that cost more than they earn and pay them down — delete, rename or extract first, rewrite only what has to stay. The burn-down counterpart
to the guards in #713.

## Important

- **Deletion is the default; rewriting is the exception.** Most long comments are unnecessary, not badly worded; the bucket order below keeps the effort on
  removal.
- **Never lose an accepted limitation.** Venue sub-package KDoc is their home (#393, #714). **Shorten how it is said; never delete what it says.** A fact that
  leaves a comment moves somewhere reviewable first — a test name, an issue, an ADR.
- **Re-wrapping is not compaction** — the caps count lines, so re-flowing passes the lint and changes nothing. Cut words, not line breaks.
- **Never silence a rule to go green.** `# comment-lint: allow <reason>` and `@Suppress("LongComment")` are for a comment that earned its length, argued in
  the PR.
- This skill edits code: [`/verify`](verify.prompt.md) before handing back, never a red tree.

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

The proof obligation is what makes the workload safe at all: `scripts/comment-lint.sh check` has to pass, `scripts/comment-density.sh report` has to show the
drop the run claims, and a comment-only change that turns a test red went further than intended.

**Your final message is the report, and there is no second turn.** The run ends the moment you stop calling tools; a closing line like _"I'll compile the
report once the checks finish"_ is the whole deliverable, and the job still reports success. Finish the work, then write the Output section as your last
message.

**Every count in the report carries the command that produced it, and a zero needs its evidence like every other number.** A run that proved each of its
zeros and left its one non-zero count unproved reported three candidates where the tree held fifty-seven; two runs minutes apart once disagreed about whether
a pattern existed, both confident, one wrong. A number without a command behind it is a guess, and a guess in a section headed _"reported for a human"_ is
the one a human acts on.

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

**`--worst N` and `--all` find different things**: ranking by lines × ratio selects the files that are dense _deliberately_, where the least is removable;
boilerplate is a few lines each across many files, and only `--all` reaches it. **Read the whole comment before touching it** — the common mistake is
compressing a paragraph that should have been deleted.

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

- **DELETE** — the boilerplate #393 removed and copying a scraper puts back: `@param document the parsed Jsoup document`, "performs no I/O", the
  fixture-and-mock setup.
- **RELOCATE, and check first** — a fenced `Example:` block in KDoc is usually a second copy of an assertion that exists. Grep its strings against the test
  tree; if the tests hold them, the block goes.
- **KEEP, and compress** — the trade-off, the outside constraint, the failure the shape avoids. The `asd-ste100` skill does the rewrite: ≤20-word sentences,
  active, present, one word per concept, ≤3 sentences.

**What makes a comment long here**: a markdown heading (a document in the wrong file — move it, leave a pointer); a date or incident narrative (keep the
conclusion, drop the forensics); history (rewrite in the present tense; a live trap gets one sentence, not its story); an argument reconstructed from the PR
thread (keep what constrains _this_ code).

### 4. What is not a smell, and must not be "fixed"

The lint already knows these; a human sweep is where they get broken. **A date inside backticks, quotes or a fenced block is data** (`"2026-05-16T20:00"`).
**"used to" is nearly always the verb.** **A pinned clock in a test and a legal date are records**, exempt from `CommentSmell`. **A long comment recording a
trade-off is not boilerplate**; fewer words, reasoning intact.

### 5. Apply, then prove it

Work file by file and re-run the checks after each one — the caps are per comment, so a single edit can move a file from four findings to none.

```bash
./gradlew detekt --console=plain -q
cd events-frontend && npx eslint .
scripts/comment-lint.sh check
```

**Show the drop rather than asserting it.** There is no baseline to commit any more, so the report is the evidence — run it before and after, and put both
totals in the report:

```bash
git add -N <any new file>          # git ls-files cannot see an untracked one
scripts/comment-density.sh report  # the TOTAL line is the number to quote
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

### 7. Ship it

**An edit that is not committed did not happen** — on a runner the job ends and still reports success. [`/open-pr`](open-pr.prompt.md) with the bucket
table as the body. **If nothing was applied, ship nothing and say so**; a sweep that found only RELOCATE and KEEP has done its job by reporting them.

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
