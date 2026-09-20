# Commit Message Generator

Write a commit message in [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/) form for the staged changes.

## Important

- `git --no-pager` on every git command; the diff is `git --no-pager diff --staged`.
- **Body lines are not limited to 72 characters.** Wrap where it reads naturally, not at a column.
- **Use the conversation history.** It holds the motivation, the root cause and the trade-offs the diff cannot show; write for someone reading `git log`
  months later.
- **`feat` only for a change a visitor to the site can see**, in a product scope: `frontend`, `events`, `promoters`, `venues`, `artists`, `importer`,
  `scraper`, `bff`, `images`, `branding`. A `feat` earns a minor (`scripts/version.sh deserved`) and opens the release notes, and `label-pr.yml` goes red on
  one outside those scopes. A new CI capability is `ci`; a chart or cluster change `chore(deploy)` or `build`; a skill or prompt `chore(agents)`; a script
  `chore(scripts)`; a rule or guide `docs(rules)` / `docs(agents)`. `fix` and `perf` take any scope — a repaired pipeline is honestly `fix(ci)`.

## The shape

```
<type>[optional scope][!]: <description>

[optional body]

[optional footer(s)]
```

- `fix` is a PATCH, `feat` a MINOR, a `!` after the type/scope or a `BREAKING CHANGE: <description>` footer a MAJOR (a minor before 1.0.0), on any type.
  Other types — `build`, `chore`, `ci`, `docs`, `refactor`, `perf`, `test` — move no version.
- The description follows the colon and space directly, in the imperative, no full stop. The body starts one blank line later, free-form paragraphs.
- Footers are git trailers (`Closes: #123`, `Refs: #123`, `Reviewed-by: …`), one blank line after the body; a token uses `-` for spaces, `BREAKING CHANGE`
  excepted, and must be uppercase for that one.
- `Closes #NNN` lives in the PR body, not here (AGENTS.md § The Backlog); a commit footer may carry it too.

```
fix(scraper): prevent racing of requests

Introduce a request id and a reference to the latest request, and dismiss
responses from any other. The timeouts that mitigated the race are gone.

Refs: #123
```
