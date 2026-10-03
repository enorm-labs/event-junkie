<script lang="ts" setup>
import { computed } from 'vue'
import type { EventSummary } from '@/api/types'
import { posterArt, type PosterGround } from '@/lib/posterArt'

/**
 * What a card shows where the venue published no flyer. That is one upcoming event in nine, so it
 * is a normal state rather than an edge case.
 *
 * It sets the title large over an outline drawing, both picked by genre family or event type, so an
 * empty card says what kind of night it is. `ClubStamp` is the alternative and loses twice: one
 * drawing repeated nine times a page is wallpaper, and it is a 50 kB chunk of the home route (#1246).
 */
const props = defineProps<{
  title?: string | null
  /** A short line above the title, such as the date. */
  eyebrow?: string | null
  eventType?: EventSummary['eventType'] | null
  /** Genre family slugs, most telling first. */
  families?: readonly string[] | null
  /** A venue's own kinds (`club`, `theatre`), drawn when it has no families. */
  venueTypes?: readonly string[] | null
  aspect?: string
}>()

// Whole class names, so Tailwind finds each one in the source.
const GROUND: Record<PosterGround, string> = {
  electronic: 'bg-poster-electronic',
  voice: 'bg-poster-voice',
  rock: 'bg-poster-rock',
  roots: 'bg-poster-roots',
  concert: 'bg-poster-concert',
  festival: 'bg-poster-festival',
  stage: 'bg-poster-stage',
  words: 'bg-poster-words',
  other: 'bg-muted',
}

const art = computed(() => posterArt(props.eventType, props.families, props.venueTypes))
</script>

<template>
  <!-- `aria-hidden`: the card's own heading and meta lines carry every word and fact set here. -->
  <div
    :class="[
      aspect ?? 'aspect-poster',
      GROUND[art.ground],
      'relative flex w-full flex-col justify-between gap-4 overflow-hidden p-4 text-foreground sm:p-6',
    ]"
    aria-hidden="true"
  >
    <!-- Foreground at low opacity: a shade lighter than the ground in dark mode, darker in light. -->
    <component
      :is="art.icon"
      :stroke-width="1.25"
      class="absolute -right-8 -bottom-10 size-56 text-foreground/10 sm:size-72"
    />
    <span class="relative font-mono text-meta tracking-eyebrow uppercase opacity-70">{{
      eyebrow
    }}</span>
    <div class="relative space-y-3">
      <!-- The counterpart to the poster's grayscale reveal: the card answers the pointer either way. -->
      <span
        class="block h-px w-10 bg-primary transition-all duration-300 motion-safe:group-hover:w-20 motion-safe:group-data-focus/poster:w-20"
      />
      <!--
        `line-clamp-4` rather than `truncate`: this is the only place the title is set large, so a
        long one is worth four lines before it is cut. The card's own heading carries the full text.
      -->
      <span class="line-clamp-4 text-page font-bold tracking-tight text-balance uppercase">{{
        title
      }}</span>
    </div>
  </div>
</template>
