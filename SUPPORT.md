# Support

Thanks for using Event Junkie. This file is the map: it says where each kind of question goes, and what to expect once it gets there. Everything here is free
and voluntary — there is no paid tier and no support contract.

> **The site is not live yet.** Event Junkie is in development and is not deployed anywhere
> (see [README §Project Status](./README.md#project-status)). So "the site is down" is not yet a thing that can happen,
> and questions today are mostly about the code, the data model, or running it locally.

## Where to go

| You want to…                                      | Go here                                                                                                                                                  |
| ------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Report wrong or missing event data**            | [Wrong or missing event data](https://github.com/enorm-labs/event-junkie/issues/new?template=wrong-event-data.yml)                                       |
| **Suggest a venue we should be importing**        | [Suggest a venue](https://github.com/enorm-labs/event-junkie/issues/new?template=new-venue.yml)                                                          |
| **Report a bug in the site or API**               | [Bug report](https://github.com/enorm-labs/event-junkie/issues/new?template=bug.yml)                                                                     |
| **Ask a question** — using it, the data, the code | [Discussions → Q&A](https://github.com/enorm-labs/event-junkie/discussions/new?category=q-a)                                                             |
| **Suggest a feature or float an idea**            | [Discussions → Ideas](https://github.com/enorm-labs/event-junkie/discussions/new?category=ideas)                                                         |
| **Report a security vulnerability**               | [Private advisory form](https://github.com/enorm-labs/event-junkie/security/advisories/new) — **never a public issue**. See [SECURITY.md](./SECURITY.md) |
| **Ask for your name or details to be removed**    | [The same private form](https://github.com/enorm-labs/event-junkie/security/advisories/new) — see [below](#artists-organisers-and-venues)                |
| **Contribute code**                               | [CONTRIBUTING.md](./CONTRIBUTING.md)                                                                                                                     |

**Questions go to Discussions, not the issue tracker.** Not to keep them at arm's length — a question is a conversation, whereas an issue is a unit of work that
can be closed. A discussion that turns out to be actionable gets converted into an issue, so nothing is lost by starting there. Blank issues are disabled for
the same reason; the forms ask for the one thing that makes a report actionable (which event, which venue, which URL), and asking afterwards usually loses the
reporter.

## The most useful thing you can report

**Event data that is wrong.** Events are read automatically from venue websites. When a venue redesigns its programme page, the importer can go quietly wrong
for weeks — nothing errors, the events simply stop being right. Nobody notices that faster than somebody who went to the show.

Include **the venue's own page for the event**. That page is what the importer reads, so it is the difference between a report we can act on and one we have to
reproduce first.

## What to expect

A single person maintains this in their own time. The honest version rather than a service-level promise:

- **A reply within a few days**, usually sooner. If a week passes with nothing, assume the notification was missed and say so on the thread — a nudge is
  welcome, not rude.
- **Wrong data gets priority.** It is the one class of problem where the site is actively misleading somebody, and it is usually a small fix.
- **"No" is a real answer**, and it comes with a reason. Not every bug is worth fixing and not every idea fits the project; you will get the reasoning rather
  than silence or an issue left open for a year.
- **Slower around venue coverage.** Adding a venue means writing and testing an importer against that site's markup. It is real work, so a suggestion is a
  candidate for the backlog rather than a queued task —
  [docs/EVENT_DATA_SOURCES.md](./docs/EVENT_DATA_SOURCES.md) shows where each one currently stands.

Security reports run on their own track, described in [SECURITY.md](./SECURITY.md).

## Artists, organisers and venues

If you are named on this site and would rather not be, **you do not need a reason and you should not have to ask in public.** Use
the [private form](https://github.com/enorm-labs/event-junkie/security/advisories/new) — it is labelled for security reports because it is currently the only
confidential channel, and it reaches the maintainer directly.

The same channel is the right one for a venue that would prefer we did not import its programme at all. That is a request, not an argument to win —
see [docs/LEGAL.md](./docs/LEGAL.md) and
[ADR-007 Web Scraping Strategy](./docs/adr/ADR-007_WEB_SCRAPING_STRATEGY.md) for how the project thinks about it.

Once `event-junkie.de` is registered, an email address will work as an alternative to the form. It does not exist yet; until then the form above is the only
private route.

## Running it yourself

Setup lives in the [README](./README.md) — JDK, Docker, `scripts/dev-env.sh`, and how to start each module. The frontend has its
own [README](./events-frontend/README.md). If it does not work, that is a documentation bug worth a
[Q&A discussion](https://github.com/enorm-labs/event-junkie/discussions/new?category=q-a): the setup instructions are only as good as the last person who
followed them.

## Code of Conduct

Every one of these channels is covered by the [Code of Conduct](./CODE_OF_CONDUCT.md). It is short, and it amounts to:
be decent to whoever reads your message.
