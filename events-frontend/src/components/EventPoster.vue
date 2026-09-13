<script lang="ts" setup>
/**
 * What a card shows where the venue published no flyer. That is one upcoming event in nine, so it
 * is a normal state rather than an edge case.
 *
 * It sets the event's own title in the brand's eyebrow idiom, which makes every empty card
 * different. `ClubStamp` is the alternative and loses twice: one drawing repeated nine times a page
 * is wallpaper, and it is a 50 kB lazy chunk belonging to the home route (see #1246).
 */
defineProps<{ title?: string | null; aspect?: string }>()
</script>

<template>
  <div
    :class="[aspect ?? 'aspect-[3/2]', 'flex w-full flex-col justify-end gap-2 bg-muted p-4']"
  >
    <!-- The counterpart to the poster's grayscale reveal: the card answers the pointer either way. -->
    <span
      class="h-px w-10 bg-primary transition-all duration-300 motion-safe:group-hover:w-20 motion-safe:group-data-focus/poster:w-20"
    />
    <!--
      `line-clamp-5` rather than `truncate`: this is the only place the title is set large, so a long
      one is worth five lines before it is cut. The card's own heading carries the full text.
    -->
    <span
      class="line-clamp-5 font-mono text-lede leading-tight tracking-eyebrow text-foreground/70 uppercase transition-colors duration-300 group-hover:text-foreground group-data-focus/poster:text-foreground"
      aria-hidden="true"
      >{{ title }}</span
    >
  </div>
</template>
