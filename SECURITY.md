# Security Policy

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Use [GitHub's private vulnerability reporting form](https://github.com/enorm-labs/event-junkie/security/advisories/new). It is confidential, it reaches the
maintainer directly, and it gives us a private place to discuss and fix the issue before anything is disclosed.

**security@event-junkie.de** works as an alternative — live since 2026-08-21, and tested in both directions. The advisory form above is still the better
route for anything you would rather not put in plain SMTP, because it is encrypted in transit and at rest and this mailbox is not.

### What to expect

This is a single-maintainer project, so the honest version rather than a corporate SLA:

- **Acknowledgement within a few days.** If you have heard nothing after a week, assume the notification was missed and ping the report again.
- **An assessment and a plan** once the report is understood — including "this is not a vulnerability, and here is why", which is a legitimate outcome and will
  come with reasoning rather than silence.
- **Credit in the release notes** for the fix, unless you would rather stay anonymous. Say which you prefer.

We will not take legal action against anyone who reports a problem in good faith, stays within the scope below, and gives us a reasonable chance to fix it
before going public.

### What is useful in a report

Whatever you have. A rough description beats no report. If you can, include: what the problem is, how to reproduce it, which URL or endpoint is affected, and
what an attacker could actually achieve with it.

## Scope

The project is **not deployed anywhere yet** (see [README §Status](./README.md#status)), so there is no production system to attack. That makes the interesting
surface the code itself:

**In scope**

- The three backend modules (`events-core`, `events-bff`, `events-importer`) and the frontend (`events-frontend`).
- Anything that would become exploitable on deployment: injection, authentication and authorisation gaps, unsafe deserialisation, SSRF in the scrapers, secrets
  committed to the repository, or a dependency vulnerability we have not noticed.
- The scrapers are worth a particular look. They fetch and parse untrusted HTML from dozens of third-party sites, which is the largest untrusted-input surface
  in the project by a wide margin.

**Out of scope**

- The third-party venue websites the importer reads from. They are not ours; please do not test against them.
- Findings that require access to a developer's machine or to the repository's secrets.
- Automated scanner output with no demonstrated impact. We already run OWASP Dependency-Check, CodeQL and Dependabot — a report that simply repeats their output
  is not useful unless you can show why it matters here.

## What we already do

So you know what ground is covered, and where a report is most likely to find something new:

- **CodeQL** analysis on every pull request (Java/Kotlin, JavaScript/TypeScript, and the Actions workflows themselves), plus the `code-quality` query suites
  on pushes to `main` and on the weekly schedule — off the pull request path, where they would compete with linters that answer in seconds.
- **OWASP Dependency-Check** on every build, failing on CVSS ≥ 7, plus a scheduled full scan.
- **Dependabot** alerts and update PRs, and **dependency review** on pull requests, with a licence policy attached.
- **gitleaks** as a pre-commit hook, to keep credentials out of the history in the first place.

## Supported versions

The project is pre-1.0 and not yet released, so only the current `main` branch is supported. There are no maintained release branches to backport to.
