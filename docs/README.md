# Documentation

Everything written down about Event Junkie, and where each thing lives. **The conventions every change is held to are in
[AGENTS.md](../AGENTS.md)** — it is written for AI agents, but it is simply this project's conventions written down, and it is the most complete
document in the repository.

## Running it — [`ops/`](ops)

The platform: standing it up, getting into it, shipping to it, and getting the data back when something goes wrong. A distinct audience from
everything below — someone with a WireGuard tunnel open, not someone writing an importer.

| Document                                             | What it covers                                                                                                      |
| ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| [ops/PLATFORM_SETUP.md](ops/PLATFORM_SETUP.md)       | The plan for running this in production: Hetzner, k3s, TLS, observability, go-live                                  |
| [ops/CLUSTER_BOOTSTRAP.md](ops/CLUSTER_BOOTSTRAP.md) | Nothing to a reconciling cluster, in order — the once-per-cluster runbook, with the traps that actually cost time   |
| [ops/RELEASING.md](ops/RELEASING.md)                 | What happens on every commit afterwards: build, publish, and Flux pulling it onto the cluster                       |
| [ops/CLUSTER_ACCESS.md](ops/CLUSTER_ACCESS.md)       | Day-to-day access to a running cluster: tunnel, kubeconfig, contexts, k9s                                           |
| [ops/BACKUPS.md](ops/BACKUPS.md)                     | What protects the database, what each layer survives, and how you know it works                                     |
| [ops/RESTORE_RUNBOOK.md](ops/RESTORE_RUNBOOK.md)     | **Restoring it** — written for someone who is reading it because something has already gone wrong                   |
| [ops/HEALTHCHECKS.md](ops/HEALTHCHECKS.md)           | The dead-man's switches: what alerts from outside the cluster, how to wire a node to one, and how to prove it fires |
| [ops/SECRETS.md](ops/SECRETS.md)                     | The six cluster secrets: which are encrypted into git, which stay hand-made, and how to recreate each               |
| [ops/DAILY_COMMANDS.md](ops/DAILY_COMMANDS.md)       | The commands you actually type, with the reasoning stripped out and linked instead                                  |
| [ops/OPENOBSERVE.md](ops/OPENOBSERVE.md)             | Operating the logs, metrics and dashboards — including the trap that silently stops ingestion                       |
| [ops/EMAIL.md](ops/EMAIL.md)                         | The role mailboxes, and the DNS that lets exactly one machine send as the domain                                    |
| [ops/COSTS.md](ops/COSTS.md)                         | Every recurring charge, where each number comes from, and which ones are still guesses                              |

## Building it

| Document                         | What it covers                                  |
| -------------------------------- | ----------------------------------------------- |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Building, running, quality checks, dependencies |
| [WORKTREES.md](WORKTREES.md)     | Running two sessions or two agents in parallel  |

## The data

| Document                                                       | What it covers                           |
| -------------------------------------------------------------- | ---------------------------------------- |
| [DATA_MODEL.md](DATA_MODEL.md)                                 | The domain model and schema              |
| [DATA_QUALITY_STRATEGY.md](DATA_QUALITY_STRATEGY.md)           | How the data is kept honest              |
| [DATA_QUALITY_PILLAR_1_PLAN.md](DATA_QUALITY_PILLAR_1_PLAN.md) | The plan for the first quality pillar    |
| [EVENT_DATA_SOURCES.md](EVENT_DATA_SOURCES.md)                 | Every venue source and its import status |

## The product

| Document                                           | What it covers                                    |
| -------------------------------------------------- | ------------------------------------------------- |
| [PRODUCT_OVERVIEW.md](PRODUCT_OVERVIEW.md)         | What Event Junkie is and does today               |
| [EVENT_SCOPE.md](EVENT_SCOPE.md)                   | Which kinds of event belong here, and which don't |
| [BRANDING.md](BRANDING.md)                         | Name, voice, visual direction                     |
| [BRAND_REFRESH_PLAN.md](BRAND_REFRESH_PLAN.md)     | Replacing the logo, then the visual pass          |
| [LOGO_IDEAS.md](LOGO_IDEAS.md)                     | Candidate marks, and the case against each        |
| [VISION_ROADMAP_IDEAS.md](VISION_ROADMAP_IDEAS.md) | Where this is going                               |

## Across all of it

| Document                         | What it covers                                                                                                                                                |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [LEGAL.md](LEGAL.md)             | Licensing, privacy, accessibility, scraping obligations. Referenced from the product docs, the ops docs and the prompts alike — which is why it has no folder |
| [CREDENTIALS.md](CREDENTIALS.md) | The credential inventory — what exists, what it unlocks and where it lives. **No secret values, ever**                                                        |
| [LINKS.md](LINKS.md)             | Every external service, console and reference this project depends on, in one place                                                                           |
| [adr/](adr)                      | Architecture decisions, with the reasoning. Flat and numbered; the numbering is the structure                                                                 |

## Elsewhere in the repository

- [infra/README.md](../infra/README.md) — the OpenTofu that declares the platform
- [deploy/charts/event-junkie/README.md](../deploy/charts/event-junkie/README.md) — the Helm chart that deploys onto it
- [events-frontend/README.md](../events-frontend/README.md) — the Vue SPA
- [perf/README.md](../perf/README.md) — performance testing with k6
