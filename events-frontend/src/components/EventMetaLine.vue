<script lang="ts" setup>
/**
 * The event page's meta line: inline items with a `·` between two of them, the way the card's meta
 * line reads (#2661). The space before the dot does not break, so a wrap never opens a line on one.
 */
import BaseBadge from '@/components/BaseBadge.vue'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'

export interface MetaItem {
  text: string
  class?: string
  /** A non-scheduled status, which must not read as a word in a grey row (#1248). */
  badge?: boolean
  /** A date the show does not happen on: postponed or cancelled (#3093). */
  struck?: boolean
  /** Where a value comes from when the source did not state it, such as a derived genre (ADR-047). */
  note?: string
}

defineProps<{ items: MetaItem[] }>()
</script>

<template>
  <p class="text-body text-muted-foreground">
    <template v-for="(item, index) in items" :key="index">
      <!-- The space stays outside the hidden dot, or a screen reader joins the items: "202614:00" (#4745). -->
      <template v-if="index > 0"
        ><span aria-hidden="true" data-meta-separator>&nbsp;·</span>{{ ' ' }}</template
      >
      <BaseBadge v-if="item.badge" variant="destructive">{{ item.text }}</BaseBadge>
      <del v-else-if="item.struck" :class="item.class">{{ item.text }}</del>
      <!-- A popover, not a title: a phone never shows a title, and the underline invites a tap (#5263). -->
      <Popover v-else-if="item.note">
        <PopoverTrigger as-child>
          <button
            :class="item.class"
            class="rounded-sm text-left underline decoration-dotted underline-offset-4 hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
            data-meta-derived
            type="button"
          >
            {{ item.text }}<span class="sr-only"> ({{ item.note }})</span>
          </button>
        </PopoverTrigger>
        <PopoverContent align="start" class="w-auto" data-meta-note>{{ item.note }}</PopoverContent>
      </Popover>
      <span v-else :class="item.class">{{ item.text }}</span>
    </template>
  </p>
</template>
