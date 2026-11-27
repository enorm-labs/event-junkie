<script lang="ts" setup>
/**
 * A detail page's outbound links as one `·`-separated line, the shape the meta line has. The
 * caller decides whether a link carries the referral tag (lib/referral.ts). [lead] is a plain
 * fact that opens the line, such as a promoter's kind (#5080). With [leadTo] it links inside the
 * site, as the kind links to the promoters of that kind (#5091).
 *
 * A link's [icon] is black or white and 21 px because Spotify's brand rules allow no other
 * colour and no smaller size; every icon follows, so the line stays even. Icons mark where each
 * link starts, so a line with them drops the dots for a gap that wraps cleanly (#5610).
 */
import { type Component, computed } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import { TEXT_LINK_CLASS } from '@/lib/utils'

export interface DetailLink {
  label: string
  url: string
  icon?: Component
}

const props = defineProps<{
  links: DetailLink[]
  lead?: string | null
  leadTo?: RouteLocationRaw | null
}>()

const withIcons = computed(() => props.links.some((link) => link.icon))
</script>

<template>
  <p
    v-if="lead || links.length"
    :class="['text-body', withIcons && 'flex flex-wrap items-center gap-x-4 gap-y-2']"
  >
    <RouterLink v-if="lead && leadTo" :class="TEXT_LINK_CLASS" :to="leadTo">{{ lead }}</RouterLink>
    <span v-else-if="lead" class="text-muted-foreground">{{ lead }}</span>
    <template v-for="(link, index) in links" :key="link.url">
      <span
        v-if="!withIcons && (index > 0 || lead)"
        aria-hidden="true"
        class="text-muted-foreground"
        >&nbsp;·
      </span>
      <a
        :href="link.url"
        :class="[TEXT_LINK_CLASS, link.icon && 'inline-flex items-center gap-1']"
        rel="noopener noreferrer"
        target="_blank"
        ><component
          :is="link.icon"
          v-if="link.icon"
          aria-hidden="true"
          class="size-5.25 shrink-0 text-foreground"
        />{{ link.label }}</a
      >
    </template>
  </p>
</template>
