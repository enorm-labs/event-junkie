<script lang="ts" setup>
/**
 * "About" — English version; `AboutView.de.md` is its counterpart, written from the concept
 * rather than translated (BRANDING.md §8). Both anchor the beta section at `#beta`,
 * because the header badge links there regardless of language.
 *
 * Markdown, compiled to a component at build time (vite.config.ts, #471). The styling the
 * paragraphs and links carried as classes lives in `ProsePage`. A `<section>` line, a blank
 * line, the Markdown, a blank line and `</section>`: without the blank lines the text inside
 * is not read as Markdown.
 */
import { RouterLink } from 'vue-router'
import ProsePage from '@/components/ProsePage.vue'
import { useLocalePath } from '@/composables/useLocalePath'
import { feedbackMailto } from '@/lib/feedback'
import { CONTROLLER } from '@/lib/legal'

const localePath = useLocalePath()
</script>

<ProsePage>

# About

Event Junkie is your guide to what's on across Berlin's venues: one feed of live events of every kind, on big stages and in small back rooms.

Browse everything at a glance and open the calendar to plan your week, so you catch the nights worth showing up for.

<section>

## What you'll find here

The rule is simple: **if a Berlin venue puts it on a stage in the evening, it belongs here.** Not just live music, and definitely not just techno. Concerts and club nights make up most of it, and stand-up and Kabarett come next. Alongside them: festivals, staged shows, readings and spoken word, screenings and open-air cinema, exhibitions from opening to closing day, and the occasional pub quiz.

Where a venue says what kind of night it is, Event Junkie takes its word for it. Punk, jazz, indie, metal, classical crossover, drag and singer-songwriter nights sit in the same feed as the club listings.

**A few things are left out on purpose.** Sport, even at the arenas that also host concerts: a basketball fixture is not what you came here for, and letting it in buries the gigs. Guided tours, workshops and yoga sessions, which you take part in instead of going to watch. Trade fairs and conferences. Children's shows, like a puppet show for a nursery group in the morning. Livestreams that only run online, because there is no room to go to. And, for now, classical concerts and orchestras: an orchestra with a conductor and soloists does not fit a model built around a headliner and a support act. That last one is a "not yet", and it is written down as an open question.

</section>

<section>

## Why this exists

Because I wanted to build **the event app Berlin deserves.**

This started long before any code. I spent years at indie parties and gigs, and every week I worked through my favourite venues' websites by hand, one after the other. I had not heard of Resident Advisor back then, and whether an indie party in a small club would have turned up there is another question.

Berlin's scene is huge and scattered. What's on lives across dozens of venue and promoter websites, each with its own layout and its own gaps. Answering something as ordinary as _what's on near me this weekend, in my genre, that I can afford?_ means a dozen browser tabs and a lot of guessing. The information is all out there. It is just never in one place.

The existing options each solve a slice of it. **Resident Advisor** is very good at electronic music, and a night at a punk bar in Friedrichshain or a cabaret room in Wilmersdorf sits outside its world. **Bandsintown and Songkick** follow _artists_, which helps if you already know who you want to see and not at all if you want to know what's happening on Thursday. Ticketing sites list what they sell, so free entry, door-only nights and the small rooms that never sold a ticket online stay invisible.

None of them offers what is missing: **one feed for all of it**, every kind of venue and every genre, free and ticketed alike. You filter by the things you decide on anyway (tonight, near me, my genre, under €15), and every entry links back to the venue's own page for tickets and the final word.

I tried several times, and as a hobby project alongside everything else it was simply too much work for one person every time. With AI agents it is possible.

</section>

<section>

## Why Berlin

Berlin is one of the best cities on this planet. Not always clean, more than a little mad, poor but sexy. Above all it is a place where you can live freely and be the person you actually are. The city is diverse and colourful, and in the evening that does not stop, it starts properly.

The club culture that grew out of that exists nowhere else in the same form. Since 2024, [Berlin's techno culture](https://www.unesco.de/staette/technokultur-in-berlin/) has been listed in Germany's nationwide inventory of intangible cultural heritage. That inventory holds living practices instead of buildings. Living also means they can disappear.

Which is what is happening. The [Clubcommission](https://www.clubcommission.de/), the association behind Berlin's club culture, calls it [Clubsterben](https://www.clubcommission.de/pressemitteilung-clubsterben-ist-wieder-an-der-tagesordnung/), the dying of the clubs: rents going up, spaces going away, rooms closing after decades. A venue nobody can find any more is not the biggest part of that. It is the part I can do something about.

The A100 motorway extension is the most visible case: the planned stretch runs through Treptow, past clubs and cultural spaces that sit on the route. Against it stands [A100 Wegbassen](https://a100-wegbassen.de/), an alliance of clubs, neighbourhood initiatives and climate groups that demands a halt to the extension and holds demos and raves to make the point. Their site is in German only.

I want to give something back. If this site puts a few people in a small room they had never heard of, it has done its job. The big names are findable without me. The others are not.

Does it really help? Does anyone need it? I don't know. But I want to try. Keeping the club culture alive is something we only manage together, night after night, by turning up.

</section>

<section>

## And to learn

The other honest reason: I built this to learn, above all about **AI-assisted development**. Most of the code here was written by AI coding agents (primarily [Claude Code](https://claude.com/claude-code)). The vision, the product decisions, the architecture and the priorities are mine. The agents implement against them, and I review every change before it lands.

I say so because it is the interesting part. A real project, with real users and real constraints, is where you find out what this way of working is good at and where it still needs a human paying attention. The whole thing is [public on GitHub](https://github.com/enorm-labs/event-junkie), conventions and prompts included, if you want to see how it was done.

</section>

<!-- The header's beta badge links here. `scroll-mt` keeps the heading clear of the
     sticky-ish header when the anchor is followed. -->
<section id="beta" class="scroll-mt-8">

## Why it says beta

Event Junkie is early. It works and I use it every week, but it is still finding its feet, and you should know what that means before you plan a night around it.

**Coverage is incomplete.** New venues are added one at a time, so a quiet night on Event Junkie is not always a quiet night in Berlin. **Details can be wrong or stale.** Events are read automatically from venue websites; when a show moves, sells out or is cancelled, the importer finds out on its next read, not immediately. Always check with the venue before you go. **Things change without notice:** pages, filters and the data behind them are all still moving.

What beta does _not_ mean: nothing here is a trial you get charged for, no data about you is sold or shared, and nothing tracks you. As much is said at the top of the <RouterLink :to="localePath('/legal/privacy')">privacy page</RouterLink>, in its short version.

Found something wrong? Telling me is the fastest way to get it fixed. Write to <a :href="feedbackMailto('Feedback on Event Junkie')">{{ CONTROLLER.email }}</a>, no account needed, or [open an issue on GitHub](https://github.com/enorm-labs/event-junkie/issues/new/choose). What has changed lately is on the [releases page](https://github.com/enorm-labs/event-junkie/releases).

</section>

</ProsePage>
