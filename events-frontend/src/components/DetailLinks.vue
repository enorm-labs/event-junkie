<script lang="ts" setup>
/**
 * A detail page's outbound links as one `·`-separated line, the shape the meta line has. The
 * caller decides whether a link carries the referral tag (lib/referral.ts).
 */
import { TEXT_LINK_CLASS } from '@/lib/utils'

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
      <a :href="link.url" :class="TEXT_LINK_CLASS" rel="noopener noreferrer" target="_blank">{{
        link.label
      }}</a>
    </template>
  </p>
</template>
