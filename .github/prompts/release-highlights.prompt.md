# Release Highlights

Write the summary that opens a release's notes: what changed for a person who visits the site to find events in Berlin. **Read-only, and the text is
the whole deliverable.** `cut-release.yml` runs this before it creates a release and puts the result above the label categories from
`.github/release.yml`; at a terminal it prints what the next release would say.

The categories below the summary list every pull request, in the maintainer's words. This summary exists because a visitor reads twenty rows there to
find the two that concern them, and those two are written in the vocabulary of the code. The summary names them, in the visitor's vocabulary, and
leaves the rest out.

## Important

- **The reader is a visitor to the site, not a maintainer.** They see the site and its data: venues, events, times, posters, filters, pages, search.
  They do not see the chart, the pipeline, the scrapers, the scanners, the headers or the agents. A change they cannot see or feel on the site is not a
  highlight, however large.
- **Type and scope are hints, not verdicts.** A `feat` in a product scope (`frontend`, `events`, `promoters`, `venues`, `importer`, `scraper`, `bff`,
  `images`, `branding`, `artists`) is a candidate, and so is a `fix` or `perf` there whose effect shows on the site — a time now correct, a search that now
  finds what it should, a page that loads faster. `ci`, `build`, `chore`, `docs`, `test`, `refactor`, `style`, `revert`, `deps` and `chore(release)` never
  qualify. A product-scope commit whose effect the visitor cannot see — a translation that is now cached instead of bought again, a counter, a retry —
  does not qualify either.
- **Write what the visitor sees now, not what the code does.** No scope names, no identifiers, no jargon: not "the BFF", not "the importer", not
  "RFC 9457", not "the overview–detail merge". Venue names are welcome; a visitor knows Kater and Sisyphos. Present tense, plain words, one idea per
  bullet.
- **Say nothing the commits do not say.** No promised follow-ups, no guessed reasons, no numbers you did not read. When a subject is too terse to write a
  visitor's sentence from, read the pull request body (`gh pr view <n> --json body --jq .body`) or the diff (`git --no-pager show --stat <sha>`), and if
  neither settles it, leave the commit out rather than guess.
- **Never write to the tree.** The one file this writes is the `--out` path, which the workflow keeps outside the checkout.
- `git` and `gh` non-interactively (`git --no-pager …`); see AGENTS.md.

## Arguments

```text
/release-highlights [since] [--out <file>] [--unattended]
```

- **`since`** — the tag to summarise from. Defaults to the latest `v*` tag: `git tag --list 'v*' --sort=-v:refname | head -1`.
- **`--out <file>`** — write the summary to this file, with `cat >"$OUT"`, and print it too. Without it, print only.
- **`--unattended`** — the runner mode; see below.

## Step 1 — Read the commits

```sh
SINCE="${1:-$(git tag --list 'v*' --sort=-v:refname | head -1)}"
git --no-pager log --no-merges --reverse --format='%h %s' "$SINCE..HEAD"
```

Every commit here is one pull request, titled in Conventional Commits, and the number in the subject or body is the pull request's. An empty range means
there is nothing to summarise: say so and stop.

## Step 2 — Sort them

Keep the commits whose effect a visitor sees, per the rules above. For each one, write down in one line what the visitor sees now. Where several commits
make one change — five venues gaining end times, three pull requests that together add a page — that is one line, not five. Where a subject reads as
code, look further before deciding, and prefer leaving out to guessing.

A breaking change (`!` in the subject, or `BREAKING CHANGE:` in the body) is kept whatever its scope. It means someone has to act, and that is the one
line a reader must not skim past.

## Step 3 — Write

The shape:

```markdown
## Highlights

<one sentence: what this release is about, for the visitor>

- **Breaking:** <what changes for a consumer, and what they have to do> (#n)
- <what the visitor sees now> (#n)
- <what the visitor sees now> (#n, #m)
```

- Three to five bullets. Six at most, and only when the sixth is as visible as the first; the categories below the summary carry the rest.
- Order: breaking changes, then new event sources, then what the site newly does, then what it now does correctly.
- One pull request number per change, in parentheses at the end; two or three when one line covers several. GitHub links them.
- The sentence is the release in one breath, not a count: "The calendar shows how long an event runs, and Sisyphos joins the list." — not "2 features and
  1 fix".

When nothing qualifies, the summary is exactly this, so a reader learns at a glance that the release is not for them:

```markdown
## Highlights

Nothing changes for visitors in this release; it is build, security and maintenance work.
```

Two subjects and the lines they become, from real releases:

| Commit subject                                                                                                                                                             | Highlight                                                                                                                 |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| `fix(bff): the events search treats %, _ and \ in the query as letters, the way the promoters search already did`                                                          | Searching for an event whose name contains `%`, `_` or `\` finds it now (#1471)                                           |
| `feat(events): give an event an optional end, stored only when the venue states one` + `feat(events): list an event until its end, and show the end and the running state` | Events show their end time when the venue publishes one, stay listed until then, and are marked as running (#1389, #1391) |

And two that are left out: `feat(ci): Nuclei runs behind ZAP in dast-k3d` — the visitor cannot see a scanner; `fix(importer): keep a translation across
re-import instead of buying it again every night` — product scope, but the site showed the same text before and after.

## Output

Print the summary and nothing else after it. With `--out`, the file holds the same text, starting with the `## Highlights` line and ending without a
trailing blank line — `cut-release.yml` joins it to the generated notes with a gap of its own.

## Unattended

`--unattended` is how `cut-release.yml` runs this. Nobody answers a question, so ask none: decide by the rules above, write the file, and make the final
message the summary itself. A final message that is not the summary — a note, a question, a promise to write it next — reads as a failure, and the
workflow then falls back to `scripts/release-highlights.sh`, which names the commits' subjects verbatim.
