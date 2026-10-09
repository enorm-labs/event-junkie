<script lang="ts" setup>
/**
 * The small pill, for a state that must stand out or a small control: a non-scheduled status, the
 * lead card's "Our pick" (#1262), the `beta` link, and a filter link on a detail page. A plain fact is a word in a meta line (#1248):
 * five pills under a title read as decoration even when each is true.
 *
 * Variants rather than per-call-site classes, so a new pill cannot invent its own colour.
 */
import { cva, type VariantProps } from 'class-variance-authority'
import type { HTMLAttributes } from 'vue'
import { cn } from '@/lib/utils'

const badgeVariants = cva('rounded-full px-2 py-0.5 text-meta font-medium', {
  variants: {
    variant: {
      /** Outlined, for a pill that is a link rather than a state. */
      outline: 'border border-border text-foreground/70',
      /** Cancelled, and the other non-scheduled statuses. */
      destructive: 'bg-destructive/10 text-destructive',
      /** Solid, for a mark that sits on a poster and must hold its colour on a grey image. */
      primary: 'bg-primary text-primary-foreground',
    },
  },
  defaultVariants: {
    variant: 'outline',
  },
})

type BadgeVariants = VariantProps<typeof badgeVariants>

const props = defineProps<{
  variant?: BadgeVariants['variant']
  class?: HTMLAttributes['class']
}>()
</script>

<template>
  <span :class="cn(badgeVariants({ variant }), props.class)">
    <slot />
  </span>
</template>
