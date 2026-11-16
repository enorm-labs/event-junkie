<script lang="ts" setup>
/**
 * A detail page's outbound links as one `·`-separated line, the shape the meta line has. The
 * caller decides whether a link carries the referral tag (lib/referral.ts). [lead] is a plain
 * fact that opens the line, such as a promoter's kind (#5080). With [leadTo] it links inside the
 * site, as the kind links to the promoters of that kind (#5091).
 */
import type { RouteLocationRaw } from 'vue-router'
import { TEXT_LINK_CLASS } from '@/lib/utils'

export interface DetailLink {
  label: string
  url: string
}

defineProps<{ links: DetailLink[]; lead?: string | null; leadTo?: RouteLocationRaw | null }>()
</script>

<template>
  <p v-if="lead || links.length" class="text-body">
    <RouterLink v-if="lead && leadTo" :class="TEXT_LINK_CLASS" :to="leadTo">{{ lead }}</RouterLink>
    <span v-else-if="lead" class="text-muted-foreground">{{ lead }}</span>
    <template v-for="(link, index) in links" :key="link.url">
      <span v-if="index > 0 || lead" aria-hidden="true" class="text-muted-foreground"
        >&nbsp;·
      </span>
      <a :href="link.url" :class="TEXT_LINK_CLASS" rel="noopener noreferrer" target="_blank">{{
        link.label
      }}</a>
    </template>
  </p>
</template>
