# Feature Ideas

Propose features a visitor would notice, which the product and the backlog do not already have. Each idea is checked against the code, the open and closed issues
and the privacy rules before it is shown.

## Important

- **This skill reads and proposes. It files nothing.** Filing is [`/new-issue`](new-issue.prompt.md), once the user has picked. A list of ideas is cheap to
  produce and expensive to triage. Do not add to the triage cost by filing ideas nobody chose.
- **An idea that already exists is not an idea.** Check every candidate against three things before you show it: the code, the open issues and the closed issues.
  An issue closed as `wontfix` is an answer. A feature that shipped last week is a documentation gap, not an idea.
- **Every claim names its evidence.** A column in `docs/architecture/schema.sql`, a commit, a file, an endpoint or an issue. Check each reference before you
  write it. A wrong issue number in an idea becomes a wrong issue number in the issue filed from it.
- **The privacy rules filter the list. They are not a footnote.** Read AGENTS.md § Privacy & GDPR first. An idea that stores something on the visitor's
  device, loads a third-party resource, makes an outbound call from the browser, or adds personal data is **a decision, not a feature**. Show it as one.
- **Respect what the project already ruled out.** Do not propose scraping Resident Advisor (its terms forbid automated access, #356), tracking, or an
  embedded third-party player. Accounts, follows and notifications are Phase 3 (#398). Propose them only as an account-free slice.
- If the user named a constraint — "simple", "frontend only", "for the roadmap" — that is the filter. Honour it.

## Steps

1. **Read what the product does today.**
    - `docs/PRODUCT_OVERVIEW.md`, the feature inventory, and its § Surfaces table
    - `README.md` § What it does and § Background, which states the mission
    - `docs/VISION_ROADMAP_IDEAS.md` and `docs/EVENT_SCOPE.md`
    - The recent visitor-facing commits:

    ```sh
    git --no-pager log --oneline --since='2 months ago' | grep -E '^[0-9a-f]+ feat'
    ```

2. **Read what is already planned.**

    ```sh
    scripts/generate-backlog-snapshot.sh     # every open issue into build/BACKLOG.md
    ```

3. **Generate candidates through several lenses.** Aim for 20 to 30 raw candidates. Most of them will not survive step 4.

    | Lens                                  | Ask                                                                                                                              |
    | ------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
    | **Common on event and listing sites** | What do visitors expect from a nightlife, concert or listings site that this one lacks? Getting there, what changed, what is new |
    | **The visitor questions**             | Which question in VISION_ROADMAP_IDEAS.md § The questions it should answer still needs more than one filter to answer?           |
    | **Data we store and do not show**     | Which column in `docs/architecture/schema.sql` never reaches a page? Which field does the BFF return that the frontend ignores?  |
    | **A pattern that already works**      | Which existing mechanism extends cheaply? The filtered RSS feed made every filtered URL a saved search, for example.             |
    | **The mission**                       | What would put a visitor in a small room they had never heard of? What would make the Clubsterben visible?                       |
    | **Reach without tracking**            | What spreads the site with no ads, no analytics and no personal data?                                                            |
    | **Venues, the press and researchers** | What could the site give back to the people whose data it shows?                                                                 |

4. **Check each candidate. Drop it, or keep it with its evidence.**
    1. Search the code for it. `git grep -i` the feature's obvious terms in `events-frontend/src`, `events-bff/src/main` and `events-core/src/main`.
    2. Search the open issues: `grep -i -E '<terms>' build/BACKLOG.md`.
    3. Search all issues, closed included: `gh issue list --state all --search '<term> in:title,body' --limit 5`. Pace the calls with `sleep 0.45`.
    4. Classify it against AGENTS.md § Privacy & GDPR: none, or the category it falls in.
    5. Estimate the size with the backlog's scale: `size:S` under half a day, `size:M` one to two days, `size:L` about a week.

    An overlap with an open issue keeps the idea only when it is a clearly separable slice, such as the account-free half of an account-bound issue. Name the
    issue it overlaps.

5. **Report.** Group the survivors. Show five ideas at most per group, best first:

    1. **Quick wins**: no accounts, no device storage, no new data source.
    2. **Event and venue pages**
    3. **Reach and site level**
    4. **Roadmap and vision**: bigger bets and decisions

    Each idea gets one table row: the idea, why it fits (with its evidence), its size, and the issue it overlaps or a privacy flag. Then:
    - **Three to file first**, with one sentence each on why.
    - **What already covers the obvious ones**: the existing issues that answer the ideas you dropped, by number.

    End by offering to file the ones the user picks through [`/new-issue`](new-issue.prompt.md), and to comment the new numbers onto the issues they overlap.

## When the user picks ideas to file

Follow [`/new-issue`](new-issue.prompt.md) for each one. These points come from the last batch:

- **Write every body first, then file.** A body that refers to another new idea uses a placeholder. Replace it with the number `gh issue create` printed.
  Check the replaced line on the filed issue.
- **A privacy or legal flag becomes a ⚖️ Decision with `needs-decision`.** The feature it would unlock is filed once the decision is made.
- **Comment on every overlapping issue** with the new number and one sentence on how the two split the work.
- **Set the board fields with one `scripts/issue-board.sh batch`**, not one call per issue.
