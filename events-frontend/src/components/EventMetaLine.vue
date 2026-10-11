<script lang="ts" setup>
/**
 * The event page's meta line: inline items with a `·` between two of them, the way the card's meta
 * line reads (#2661). The space before the dot does not break, so a wrap never opens a line on one.
 */
import BaseBadge from '@/components/BaseBadge.vue'

export interface MetaItem {
  text: string
  class?: string
  /** A non-scheduled status, which must not read as a word in a grey row (#1248). */
  badge?: boolean
  /** A date the event left, such as a postponed show's (#3093). */
  struck?: boolean
}

defineProps<{ items: MetaItem[] }>()
</script>

<template>
  <p class="text-body text-muted-foreground">
    <template v-for="(item, index) in items" :key="index">
      <span v-if="index > 0" aria-hidden="true" data-meta-separator>&nbsp;· </span>
      <BaseBadge v-if="item.badge" variant="destructive">{{ item.text }}</BaseBadge>
      <del v-else-if="item.struck" :class="item.class">{{ item.text }}</del>
      <span v-else :class="item.class">{{ item.text }}</span>
    </template>
  </p>
</template>
