# Refactor

Change the shape of the code without changing what it does. The **acting** counterpart to [`/codebase-audit`](codebase-audit.prompt.md), which measures the same
ground and never mutates — the same pairing as [`/security-report`](security-report.prompt.md) and [`/security-triage`](security-triage.prompt.md). Use the audit
to find out where the weight is; use this one to move it.

## Important

- **This command mutates.** Invoking it **is** the permission to edit code and open a pull request, the way [`/open-pr`](open-pr.prompt.md) is the permission to
  commit and push. Do not invoke it on your own initiative.
- **Behaviour-preserving is the whole contract, and the test suite is the only proof of it.** A refactor that changes one assertion is not a refactor, it is a
  behaviour change wearing the word. If a test has to move, say so in the report and treat it as a finding rather than as housekeeping.
- **Never mix a refactor with a fix.** A pull request that reshapes a class _and_ corrects it gives a reviewer no way to tell which edit caused which diff, and
  no way to revert half of it. Ship the refactor, then the fix, in that order.
- **The safety net is real but not uniform.** Backend coverage is high and the fixture suite is genuinely load-bearing; `infra/` and `deploy/` have syntax gates
  and no correctness ones (see [ci-cd.instructions.md](../instructions/ci-cd.instructions.md)). Refactor confidently where tests watch, and barely at all where
  they do not.

## Usage

```
/refactor                     # findings from the current diff — the default
/refactor <path>              # one file, package or directory
/refactor --audit             # run /codebase-audit first, then act on its top findings
```

## Where the targets come from

Do not invent findings. Every change here starts from something that was already measured:

```bash
./gradlew detekt --console=plain -q                    # complexity, long methods, naming
./gradlew :events-importer:detektMain                  # the type-resolution rules CI does not run — see #407
./gradlew koverLog                                     # coverage per module, the before number
find . -name '*.kt' -not -path '*/build/*' | xargs wc -l | sort -rn | head -30
```

`/codebase-audit` already turns those numbers into a ranked list, and its rubric is the one to use. **Size is a smell, not a verdict** — a long file holding one
cohesive responsibility is finished, and splitting it produces churn and a worse name.

## What is in scope

| Change                      | In scope because                                                                  |
| --------------------------- | --------------------------------------------------------------------------------- |
| **Rename**                  | A better name deletes a comment, and a name cannot go stale                       |
| **Extract function**        | The extracted name carries what an inline comment was saying                      |
| **Extract / split class**   | A file holding two responsibilities, each with its own reason to change           |
| **Deduplicate**             | The same logic in three scrapers, where one shared helper already has a home      |
| **Delete dead code**        | Unreferenced, and git holds it                                                    |
| **Inline a needless hop**   | A one-line wrapper that only forwards, named no better than the thing it forwards |
| **Narrow a public surface** | `internal` or `private` where nothing outside the module calls it                 |

## What is not in scope, and why each one bites

- **Anything that changes an API shape.** A BFF response field, a database column, a public function signature another module calls. Those are features or
  migrations and belong to an issue with a plan.
- **Dependency changes.** [`/update-dependencies`](update-dependencies.prompt.md) owns them, and a bump inside a refactor makes the refactor unrevertable.
- **Comment volume.** [`/compact-comments`](compact-comments.prompt.md) owns that, and it classifies before it edits. Deleting comments while reshaping code is
  how a load-bearing _why_ leaves without anyone reading it.
- **A deliberate, documented decision.** The ADRs and the scrapers' KDoc record why things are the shape they are. Something an ADR decided is not a smell; if
  the ADR is wrong, that is an issue, said out loud and separately.
- **Anything under `infra/` that reaches `user_data`.** `walg_version` and `k3s_version` are force-new attributes — editing what feeds `bootstrap.env` plans a
  node replacement, production included. That is not a refactor whatever the diff looks like.

## The one that costs an afternoon

**A change to shared normalization is never local, and the tests do not tell you so.** Slug generation, genre normalization, artist-name mapping and price
parsing are called by every importer. A rename inside one of them compiles, passes the whole suite, and still changes the rows that land in the database —
because the fixtures assert the parser's output, not the shape of the corpus it runs against.

The check is not a test, it is a re-seed: `/importer-smoke <slug> --full`, then diff the row counts against the previous full run and treat a drop over 20% at
any source as a finding. [`/start-issue`](start-issue.prompt.md) asks the same question for the same reason, and it is usually the difference between an hour
and a day.

So: **if the change reaches `SlugGenerator`, `GenreNormalizer`, `ArtistNameMapping`, `MoneyExtensions` or anything else every importer calls, the re-seed is
part of the work.** If you cannot run one, the honest move is to stop and say which finding you left, not to ship the edit and hope.

## Steps

1. **Take the before numbers.** `./gradlew koverLog` and the detekt finding count. A refactor that quietly drops coverage is one that deleted a tested branch.
2. **One concern per commit.** Rename, then extract, then dedupe — each verifiable on its own. A single commit doing all three is one nobody can review or
   bisect.
3. **Apply, and re-run the suite after each one.** The suite is the proof; running it once at the end tells you something broke and not what.
4. **Read the diff as a reviewer would.** If a hunk cannot be explained as "same behaviour, better shape", it does not belong in this branch.
5. **Verify.** [`/verify`](verify.prompt.md) in full — this touches code, so the backend gate, the frontend gate and the comment checks all apply. Coverage must
   not fall. **CI compiles with `-PwarningsAsErrors` and local builds do not**, so a green local build can still fail `Build & Test`; a refactor that leaves an
   unused import is the classic way to meet that.
6. **Ship** with [`/open-pr`](open-pr.prompt.md), and say in the body what the shape was before.

## Running unattended

[`agent-refactor.yml`](../workflows/agent-refactor.yml) invokes this prompt as `/refactor --unattended` from a runner, where nobody is reading the diff as it is
made. Three of the rules above stop being advice and become hard limits.

- **Nothing that reaches shared normalization.** The re-seed above is the only check that would catch a regression there, it needs a database and a network
  scrape, and the runner has neither. Findings in that code get reported, never applied.
- **One concern, and a small one.** A rename, an extraction, a deduplication, a dead-code deletion. Not a class split, not a package move, not a reshaping that
  touches more than a handful of files — a large diff that nobody watched being made is one a reviewer has to re-derive from scratch, and that costs more than
  the finding was worth.
- **The suite is the gate, not the goal.** If `./gradlew build` goes red, revert the change and report it as a finding. Do not fix the test to match the new
  shape: that is exactly how a behaviour change ships as a refactor.
- **Report the findings you did not act on**, with the reason — out of scope, needs a re-seed, too large for one unwatched diff. A run that quietly applied the
  two easy findings and said nothing about the six real ones reads exactly like a clean codebase.

`--dry-run` on top of it opens no pull request and writes the whole report to the job summary. That is the mode to use first, and after any change to this
section.

**Your final message is the report, and there is no second turn.** The run ends the moment you stop calling tools, so a closing line like _"I'll compile the
report once the checks finish"_ ends it with that sentence as the whole deliverable — and the job still reports success. There is nobody to hand off to and
nothing to wait for: no reviewer reads the transcript, no follow-up prompt arrives, and any work you plan but do not do in this turn is simply lost. Finish the
work, then write the Output section below as your last message. This has already happened once, on a `--all` sweep that ended waiting for classification agents
it had no tool to spawn.

## Output

Lead with the shape, not the file list:

```
RENAME     3   parserFor → scraperFor, and the comment it deleted
EXTRACT    2   date-range parsing out of two scrapers, into ScrapingExtensions
DELETE     1   unreferenced ArtistNameMapping.legacyLookup
──────────────
6 changes across 5 files, 0 assertions touched

kover      82.4% → 82.6%
detekt     14 findings → 9
```

Then, in order of what a reviewer needs:

- **What was left, and why.** Anything out of scope, anything needing a re-seed, anything judged too large. This is the half that makes the report trustworthy.
- **Any test that moved**, with the argument for why it was not a behaviour change. If there is no such argument, the change should not have shipped.
- **What `/verify` did**, in full, including the coverage numbers either side.
