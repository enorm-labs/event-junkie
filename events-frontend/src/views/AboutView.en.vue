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
  <main class="mx-auto max-w-3xl space-y-4 p-8">
    <h1 class="text-3xl font-bold tracking-tight">About</h1>
    <p class="text-muted-foreground">
      Event Junkie is your guide to what's on across Berlin's venues — a single, always-fresh feed
      of live events, from concerts and club nights to festivals, parties, and the odd quiz night.
    </p>
    <p class="text-muted-foreground">
      Browse everything at a glance, filter by genre or type to home in on your scene, and open the
      calendar to plan your week. Every event links through to its venue, lineup, and artists, so
      you can dig into the details — and never miss the nights worth showing up for.
    </p>
    <p class="text-muted-foreground">
      The focus is music of every stripe — big stages and small back rooms alike, not just techno —
      all across Berlin.
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
        Where a venue says what kind of night it is, we take its word for it rather than
        second-guess the label. Punk, jazz, indie, metal, classical crossover, drag and
        singer-songwriter nights all sit in the same feed as the club listings — which is the whole
        point.
      </p>
      <p class="text-muted-foreground">
        <strong class="font-medium text-foreground">A few things are deliberately left out.</strong>
        Sport, even at the arenas that also host concerts — a basketball fixture is not what you
        came here for, and letting them in buries the gigs. Guided tours, workshops and yoga
        sessions: things you take part in rather than go and see. Trade fairs and conferences. And,
        for now, classical concerts and orchestras — not because they don't belong, but because an
        orchestra with a conductor and soloists doesn't fit a model built around a headliner and a
        support act. That one is a "not yet", and it is written down as an open question rather than
        quietly dropped.
      </p>
    </section>

    <section class="space-y-4 pt-4">
      <h2 class="text-2xl font-bold tracking-tight">Why this exists</h2>
      <p class="text-muted-foreground">
        Because I wanted to build
        <strong class="font-medium text-foreground">the event app Berlin deserves.</strong>
      </p>
      <p class="text-muted-foreground">
        Berlin's scene is enormous and completely scattered. What's on lives across dozens of venue
        and promoter websites, each with its own layout and its own gaps. Answering something as
        ordinary as <em>what's on near me this weekend, in my genre, that I can afford?</em> means a
        dozen browser tabs and a lot of guessing. The information exists; it just isn't anywhere you
        can look at it all at once.
      </p>
      <p class="text-muted-foreground">
        The existing options each solve a slice of that.
        <strong class="font-medium text-foreground">Resident Advisor</strong> is genuinely excellent
        at what it does — but what it does is electronic music, and a night at a punk bar in
        Friedrichshain or a cabaret room in Wilmersdorf isn't its world.
        <strong class="font-medium text-foreground">Bandsintown and Songkick</strong> follow
        <em>artists</em>: brilliant if you already know who you want to see, no help at all if you
        want to know what's happening on Thursday. Ticketing sites list what they sell, which
        quietly excludes free entry, door-only nights and the small rooms that never sold a ticket
        online in their lives.
      </p>
      <p class="text-muted-foreground">
        So the gap isn't a better version of any of those. It's the thing none of them tries to be:
        <strong class="font-medium text-foreground">one feed for all of it</strong> — every kind of
        venue, every genre, free and ticketed alike, filterable by the things you actually decide on
        (tonight, near me, my genre, under €15), always linking back to the venue's own page for
        tickets and the final word.
      </p>
    </section>

    <section class="space-y-4 pt-4">
      <h2 class="text-2xl font-bold tracking-tight">And to learn</h2>
      <p class="text-muted-foreground">
        The other honest reason: I built this to learn — above all about
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
        decisions, the architecture and the priorities are mine; the agents implement against them,
        and every change is reviewed before it lands.
      </p>
      <p class="text-muted-foreground">
        That is not a disclaimer — it's the interesting part. A real project with real users and
        real constraints turns out to be the only way to find out what this way of working is
        genuinely good at and where it still needs a human paying attention. The whole thing is
        <a
          class="text-foreground underline underline-offset-4"
          href="https://github.com/enorm-labs/event-junkie"
          rel="noopener"
          target="_blank"
        >
          open source
        </a>
        , conventions and prompts included, if you want to see how it was done.
      </p>
    </section>

    <!-- The header's beta badge links here (Phase 4). `scroll-mt` keeps the heading clear of the
         sticky-ish header when the anchor is followed. -->
    <section id="beta" class="scroll-mt-8 space-y-4 pt-4">
      <h2 class="text-2xl font-bold tracking-tight">Why it says beta</h2>
      <p class="text-muted-foreground">
        Event Junkie is early. It works, we use it, and it is genuinely useful — but it is still
        finding its feet, and you should know what that means before you plan a night around it.
      </p>
      <p class="text-muted-foreground">
        <strong class="font-medium text-foreground">Coverage is incomplete.</strong> New venues are
        added one at a time, so a quiet night on Event Junkie is not always a quiet night in Berlin.
        <strong class="font-medium text-foreground">Details can be wrong or stale.</strong> Events
        are read automatically from venue websites; when a show moves, sells out or is cancelled, we
        find out on our next read, not immediately. Always check with the venue before you go.
        <strong class="font-medium text-foreground">Things change without notice</strong> — pages,
        filters and the data behind them are all still moving.
      </p>
      <p class="text-muted-foreground">
        What beta does <em>not</em> mean: nothing here is a trial you get charged for, no data about
        you is sold or shared, and nothing tracks you — see the
        <RouterLink
          :to="localePath('/legal/privacy')"
          class="text-foreground underline underline-offset-4"
        >
          privacy page
        </RouterLink>
        , which is short for exactly that reason.
      </p>
      <p class="text-muted-foreground">
        Found something wrong? Telling us is the fastest way to fix it —
        <a
          class="text-foreground underline underline-offset-4"
          href="https://github.com/enorm-labs/event-junkie/issues/new/choose"
          rel="noopener"
          target="_blank"
        >
          open an issue
        </a>
        . What has changed lately is on the
        <a
          class="text-foreground underline underline-offset-4"
          href="https://github.com/enorm-labs/event-junkie/releases"
          rel="noopener"
          target="_blank"
        >
          releases page
        </a>
        .
      </p>
    </section>
  </main>
</template>
