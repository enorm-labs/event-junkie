<script lang="ts" setup>
/**
 * "About" — English version; `AboutView.de.vue` is its counterpart, written from the concept
 * rather than translated (BRANDING.md §8). Both anchor the beta section at `#beta`,
 * because the header badge links there regardless of language.
 */
import { RouterLink } from 'vue-router'
import { useLocalePath } from '@/composables/useLocalePath'

const localePath = useLocalePath()
</script>

<template>
  <main class="mx-auto max-w-3xl space-y-6 p-4 sm:p-8">
    <h1 class="text-3xl font-bold tracking-tight">About</h1>
    <p class="text-muted-foreground">
      Event Junkie is your guide to what's on across Berlin's venues: one feed of live events, from
      concerts and club nights to festivals, parties and the odd quiz night.
    </p>
    <p class="text-muted-foreground">
      Browse everything at a glance, filter by genre or type to find your scene, and open the
      calendar to plan your week. Every event links through to its venue, lineup and artists, so you
      can dig into the details and catch the nights worth showing up for.
    </p>
    <p class="text-muted-foreground">
      The focus is music of every stripe, on big stages and in small back rooms, all across Berlin.
    </p>

    <section class="space-y-4 pt-4">
      <h2 class="text-2xl font-bold tracking-tight">What you'll find here</h2>
      <p class="text-muted-foreground">
        The rule is simple:
        <strong class="font-medium text-foreground"
          >if a Berlin venue puts it on a stage in the evening, it belongs here.</strong
        >
        Not just live music, and definitely not just techno. Concerts and club nights make up most
        of it, alongside parties, festivals, staged shows and cabaret, readings and spoken word,
        screenings and open-air cinema, exhibition openings, and the occasional pub quiz.
      </p>
      <p class="text-muted-foreground">
        Where a venue says what kind of night it is, we take its word for it. Punk, jazz, indie,
        metal, classical crossover, drag and singer-songwriter nights sit in the same feed as the
        club listings.
      </p>
      <p class="text-muted-foreground">
        <strong class="font-medium text-foreground">A few things are left out on purpose.</strong>
        Sport, even at the arenas that also host concerts: a basketball fixture is not what you came
        here for, and letting it in buries the gigs. Guided tours, workshops and yoga sessions,
        which you take part in instead of going to watch. Trade fairs and conferences. And, for now,
        classical concerts and orchestras: an orchestra with a conductor and soloists does not fit a
        model built around a headliner and a support act. That last one is a "not yet", and it is
        written down as an open question.
      </p>
    </section>

    <section class="space-y-4 pt-4">
      <h2 class="text-2xl font-bold tracking-tight">Why this exists</h2>
      <p class="text-muted-foreground">
        Because I wanted to build
        <strong class="font-medium text-foreground">the event app Berlin deserves.</strong>
      </p>
      <p class="text-muted-foreground">
        This started long before any code. I spent years at indie parties and gigs, and every week I
        worked through my favourite venues' websites by hand, one after the other. I had not heard
        of Resident Advisor back then, and whether an indie party in a small club would have turned
        up there is another question.
      </p>
      <p class="text-muted-foreground">
        Berlin's scene is huge and scattered. What's on lives across dozens of venue and promoter
        websites, each with its own layout and its own gaps. Answering something as ordinary as
        <em>what's on near me this weekend, in my genre, that I can afford?</em> means a dozen
        browser tabs and a lot of guessing. The information is all out there. It is just never in
        one place.
      </p>
      <p class="text-muted-foreground">
        The existing options each solve a slice of it.
        <strong class="font-medium text-foreground">Resident Advisor</strong> is very good at
        electronic music, and a night at a punk bar in Friedrichshain or a cabaret room in
        Wilmersdorf sits outside its world.
        <strong class="font-medium text-foreground">Bandsintown and Songkick</strong> follow
        <em>artists</em>, which helps if you already know who you want to see and not at all if you
        want to know what's happening on Thursday. Ticketing sites list what they sell, so free
        entry, door-only nights and the small rooms that never sold a ticket online stay invisible.
      </p>
      <p class="text-muted-foreground">
        The gap is the thing none of them tries to be:
        <strong class="font-medium text-foreground">one feed for all of it</strong>, every kind of
        venue and every genre, free and ticketed alike. You filter by the things you decide on
        anyway (tonight, near me, my genre, under €15), and every entry links back to the venue's
        own page for tickets and the final word.
      </p>
      <p class="text-muted-foreground">
        I wanted to build it for years. I started several times, and as a hobby project alongside
        everything else it was simply too much work for one person every time. With AI agents it is
        possible.
      </p>
    </section>

    <section class="space-y-4 pt-4">
      <h2 class="text-2xl font-bold tracking-tight">Why Berlin</h2>
      <p class="text-muted-foreground">
        Berlin is one of the best cities on this planet. Not always clean, more than a little mad,
        poor but sexy. Above all it is a place where you can live freely and be the person you
        actually are. The city is diverse and colourful, and in the evening that does not stop, it
        starts properly.
      </p>
      <p class="text-muted-foreground">
        The club culture that grew out of that exists nowhere else in the same form. Since 2024,
        <a
          class="text-foreground underline underline-offset-4"
          href="https://www.unesco.de/staette/technokultur-in-berlin/"
          rel="noopener"
          target="_blank"
        >
          Berlin's techno culture
        </a>
        has been listed in Germany's nationwide inventory of intangible cultural heritage. That
        inventory holds living practices instead of buildings. Living also means they can disappear.
      </p>
      <p class="text-muted-foreground">
        Which is what is happening. The
        <a
          class="text-foreground underline underline-offset-4"
          href="https://www.clubcommission.de/"
          rel="noopener"
          target="_blank"
        >
          Clubcommission</a
        >, the association behind Berlin's club culture, calls it
        <a
          class="text-foreground underline underline-offset-4"
          href="https://www.clubcommission.de/pressemitteilung-clubsterben-ist-wieder-an-der-tagesordnung/"
          rel="noopener"
          target="_blank"
        >
          Clubsterben</a
        >, the dying of the clubs: rents going up, spaces going away, rooms closing after decades. A
        venue nobody can find any more is not the biggest part of that. It is the part I can do
        something about.
      </p>
      <p class="text-muted-foreground">
        The A100 motorway extension is the most visible case: the planned stretch runs through
        Treptow, past clubs and cultural spaces that sit on the route. Against it stands
        <a
          class="text-foreground underline underline-offset-4"
          href="https://a100-wegbassen.de/"
          rel="noopener"
          target="_blank"
        >
          A100 Wegbassen</a
        >, an alliance of clubs, neighbourhood initiatives and climate groups that demands a halt to
        the extension and holds demos and raves to make the point. Their site is in German only.
      </p>
      <p class="text-muted-foreground">
        I want to give something back. If this site puts a few people in a small room they had never
        heard of, it has done its job. The big names are findable without me. The others are not.
      </p>
      <p class="text-muted-foreground">
        Does it really help? Does anyone need it? I don't know. But I want to try. Keeping the club
        culture alive is something we only manage together, night after night, by turning up.
      </p>
    </section>

    <section class="space-y-4 pt-4">
      <h2 class="text-2xl font-bold tracking-tight">And to learn</h2>
      <p class="text-muted-foreground">
        The other honest reason: I built this to learn, above all about
        <strong class="font-medium text-foreground">AI-assisted development</strong>. Most of the
        code here was written by AI coding agents (primarily
        <a
          class="text-foreground underline underline-offset-4"
          href="https://claude.com/claude-code"
          rel="noopener"
          target="_blank"
        >
          Claude Code
        </a>
        ), working from conventions and prompts kept in the repository. The vision, the product
        decisions, the architecture and the priorities are mine. The agents implement against them,
        and I review every change before it lands.
      </p>
      <p class="text-muted-foreground">
        I say so because it is the interesting part. A real project, with real users and real
        constraints, is where you find out what this way of working is good at and where it still
        needs a human paying attention. The whole thing is
        <a
          class="text-foreground underline underline-offset-4"
          href="https://github.com/enorm-labs/event-junkie"
          rel="noopener"
          target="_blank"
        >
          open source</a
        >, conventions and prompts included, if you want to see how it was done.
      </p>
    </section>

    <!-- The header's beta badge links here. `scroll-mt` keeps the heading clear of the
         sticky-ish header when the anchor is followed. -->
    <section id="beta" class="scroll-mt-8 space-y-4 pt-4">
      <h2 class="text-2xl font-bold tracking-tight">Why it says beta</h2>
      <p class="text-muted-foreground">
        Event Junkie is early. It works and we use it every week, but it is still finding its feet,
        and you should know what that means before you plan a night around it.
      </p>
      <p class="text-muted-foreground">
        <strong class="font-medium text-foreground">Coverage is incomplete.</strong> New venues are
        added one at a time, so a quiet night on Event Junkie is not always a quiet night in Berlin.
        <strong class="font-medium text-foreground">Details can be wrong or stale.</strong> Events
        are read automatically from venue websites; when a show moves, sells out or is cancelled, we
        find out on our next read, not immediately. Always check with the venue before you go.
        <strong class="font-medium text-foreground">Things change without notice:</strong> pages,
        filters and the data behind them are all still moving.
      </p>
      <p class="text-muted-foreground">
        What beta does <em>not</em> mean: nothing here is a trial you get charged for, no data about
        you is sold or shared, and nothing tracks you. As much is said at the top of the
        <RouterLink
          :to="localePath('/legal/privacy')"
          class="text-foreground underline underline-offset-4"
        >
          privacy page</RouterLink
        >, in its short version.
      </p>
      <p class="text-muted-foreground">
        Found something wrong? Telling us is the fastest way to get it fixed:
        <a
          class="text-foreground underline underline-offset-4"
          href="https://github.com/enorm-labs/event-junkie/issues/new/choose"
          rel="noopener"
          target="_blank"
        >
          open an issue</a
        >. What has changed lately is on the
        <a
          class="text-foreground underline underline-offset-4"
          href="https://github.com/enorm-labs/event-junkie/releases"
          rel="noopener"
          target="_blank"
        >
          releases page</a
        >.
      </p>
    </section>
  </main>
</template>
