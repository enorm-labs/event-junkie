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

The site is **deployed but not public yet** (see [README §Status](./README.md#status)): staging has no public address, and production serves nothing until
the domain is pointed at it at go-live. Until then there is no running system to test against, and the interesting surface is the code itself:

**In scope**

- The three backend modules (`events-core`, `events-bff`, `events-importer`) and the frontend (`events-frontend`).
- The Helm chart in `deploy/` and the OpenTofu in `infra/` — they are the deployment, and a misconfiguration there is as real as one in code.
- Anything that becomes exploitable once the site is public: injection, authentication and authorisation gaps, unsafe deserialisation, SSRF in the scrapers,
  secrets committed to the repository, or a dependency vulnerability we have not noticed.
- The scrapers are worth a particular look. They fetch and parse untrusted HTML from dozens of third-party sites, which is the largest untrusted-input surface
  in the project by a wide margin.
- Once `event-junkie.de` is live, the public site and its API. Read-only probing is fine; anything that degrades the service for other visitors is not.

**Out of scope**

- The third-party venue websites the importer reads from. They are not ours; please do not test against them.
- Findings that require access to a developer's machine, to the cluster, or to the repository's secrets.
- Denial of service, rate-limit exhaustion, or anything else whose only demonstration is making the site slow or unavailable.
- Automated scanner output with no demonstrated impact. We already run OWASP Dependency-Check, CodeQL, Dependabot and Renovate — a report that simply repeats their output
  is not useful unless you can show why it matters here.

## What we already do

So you know what ground is covered, and where a report is most likely to find something new:

- **CodeQL** analysis on every pull request (Java/Kotlin, JavaScript/TypeScript, and the Actions workflows themselves).
- **OWASP Dependency-Check** nightly, failing on CVSS ≥ 7. Pull requests run it too, but as an informational check that cannot block a merge — the NVD API
  is too unreliable to hold a pull request hostage — so the nightly scan is the one that counts.
- **Dependabot** alerts and update PRs, and **dependency review** on pull requests, with a licence policy attached.
- **Renovate** for the versions no Dependabot ecosystem can express — Flux and the charts it installs (cert-manager among them), container images pinned in
  plain Kubernetes manifests, the gitleaks hook itself, and the Gradle wrapper. These are the components that hold credentials and run in the cluster, rather
  than libraries the application links against.
- **gitleaks** as a pre-commit hook, to keep credentials out of the history in the first place.

## Supported versions

The project is pre-1.0. Releases are cut from `main` as tags and there are no maintained release branches, so a fix lands on `main` and ships in the next
release rather than being backported. Only the [latest release](https://github.com/enorm-labs/event-junkie/releases/latest) and `main` are supported.
