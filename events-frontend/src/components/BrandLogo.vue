<script lang="ts" setup>
import EjBadge from '@/components/EjBadge.vue'
// Brand lockup. Badge and wordmark are never shown together: they are the same two letters, so
// together they are tautological. Splitting them across `sm` also solves a constraint that already
// exists — the header packs a lockup, a beta badge, four nav links and two icon controls, and at
// ~390px that row overflowed (guarded by `header nav fits its viewport` in e2e/smoke.spec.ts). The
// badge is narrower than the wordmark, which is what mobile can afford.
// See docs/BRANDING.md §4b.

withDefaults(
  defineProps<{
    /**
     * Wordmark at every width, and no badge. The header swaps the two at `sm`; the footer stacks its
     * columns and has the room, so it takes the wordmark throughout — a badge on its own there would
     * just read as a stray icon.
     */
    alwaysShowWordmark?: boolean
  }>(),
  { alwaysShowWordmark: false },
)
</script>

<template>
  <span class="inline-flex items-center gap-2">
    <!-- Below `sm` only. Hidden from `sm` up, where the wordmark takes over. -->
    <EjBadge v-if="!alwaysShowWordmark" class="h-6 shrink-0 sm:hidden" />
    <!-- Stays in the a11y tree at every width (sr-only when the badge is showing), so the link's
         accessible name is always "Event Junkie". -->
    <span
      :class="alwaysShowWordmark ? '' : 'sr-only sm:not-sr-only'"
      class="text-lg font-bold tracking-tight text-foreground"
    >
      Event <span class="text-primary">Junkie</span>
    </span>
  </span>
</template>
