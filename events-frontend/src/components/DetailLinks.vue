<script lang="ts" setup>
/**
 * A detail page's outbound links as one `·`-separated line, the shape the meta line has. The
 * caller decides whether a link carries the referral tag (lib/referral.ts).
 */
export interface DetailLink {
  label: string
  url: string
}

defineProps<{ links: DetailLink[] }>()
</script>

<template>
  <p v-if="links.length" class="text-body">
    <template v-for="(link, index) in links" :key="link.url">
      <span v-if="index > 0" aria-hidden="true" class="text-muted-foreground">&nbsp;· </span>
      <a
        :href="link.url"
        class="text-primary underline-offset-4 hover:underline"
        rel="noopener noreferrer"
        target="_blank"
        >{{ link.label }}</a
      >
    </template>
  </p>
</template>
