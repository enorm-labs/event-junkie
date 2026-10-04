# Design

Where the design decisions live, and which file to read for what.

**The constraints are in [`.github/instructions/design.instructions.md`](../.github/instructions/design.instructions.md).** That file loads itself whenever an
agent touches `events-frontend/src/`. It is the file that has to be right, because it is the file that is read while the code is written.

It carries the colour tokens with their literal values, the seven type steps, the spacing and widths, the component rules and the forbidden list.
`.claude/rules/design.md` is a symlink to it, so Claude Code and GitHub Copilot read one copy.

**It cannot be a pointer to this page.** An `@` include inside a rule file is expanded at launch, whatever the file's `paths:` say. A pointer-style rule
therefore loads its target into every session and the path scoping buys nothing. It fails silently: the content is there, merely always there. So the
constraints live inline in the rule, and this page says where.

## Which file answers which question

| Question                                | File                                                                                             |
| --------------------------------------- | ------------------------------------------------------------------------------------------------ |
| May I do this? What is the token?       | [design.instructions.md](../.github/instructions/design.instructions.md)                         |
| Why was it decided that way?            | [BRANDING.md §5](BRANDING.md)                                                                    |
| How do I write the component?           | [vue.instructions.md](../.github/instructions/vue.instructions.md)                               |
| What does the accessibility gate check? | [vue.instructions.md](../.github/instructions/vue.instructions.md), and [LEGAL.md §12](LEGAL.md) |

The split between the first two is the point. BRANDING.md argues at length, in prose, and prose is not a constraint. "Nightlife, editorial, a little
nocturnal" is a vibe. Where a vibe and a rule conflict, a model sides with the vibe. BRANDING.md keeps the argument. The rule file keeps the answer.

## Changing a rule

The forbidden list is the part that does the work, and a list written today freezes taste at today. The rule file carries the procedure for removing a line.
Build the thing on a branch, look at it against real data, then delete the row with a picture in the pull request.

Two candidates in the de-slop run were decided that way. The pulsing live dot in [#1242](https://github.com/enorm-labs/event-junkie/issues/1242) was kept. The
double-width lead tile in [#1247](https://github.com/enorm-labs/event-junkie/issues/1247) was built, screenshotted and dropped.

## Pictures of the result

[`screenshots/`](screenshots/) holds the product as it renders, with the date each was taken. They go stale when the design changes, not when the data does.
