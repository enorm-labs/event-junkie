<script lang="ts" setup>
/**
 * The pulsing dot for something happening now: an event on the card and the row (#358), a set in
 * the running order. The label is read out in place of the dot.
 */
defineProps<{ label: string }>()
</script>

<template>
  <span class="relative flex size-2 shrink-0">
    <span
      class="absolute inline-flex size-full rounded-full bg-primary opacity-75 motion-safe:animate-ping"
    />
    <span class="relative inline-flex size-2 rounded-full bg-primary" />
    <span class="sr-only">{{ label }}</span>
  </span>
</template>
