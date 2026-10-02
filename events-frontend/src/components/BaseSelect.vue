<script lang="ts" setup>
/**
 * A native `<select>` carrying the same field chrome as {@link BaseInput}, used by the filter
 * bars. Native for the same reasons — the platform's own dropdown on mobile, and an e2e suite
 * that drives these with Playwright's `selectOption` — so this is not the registry's Reka-based
 * `Select`, which renders a listbox instead of a real form control.
 *
 * The options go in the default slot; `aria-label` and the `@change` handler fall through. The
 * platform's arrow is replaced by the chevron `MultiSelectFilter` draws, so the two kinds of filter
 * sitting side by side read as one control (#2347).
 */
import type { HTMLAttributes } from 'vue'
import { ChevronDown } from '@lucide/vue'
import { cn, FIELD_CLASS } from '@/lib/utils'

// The wrapper takes `class`, so a grid or width utility sizes the whole field; everything else,
// `aria-label` and `@change` included, belongs on the select itself.
defineOptions({ inheritAttrs: false })

const props = defineProps<{
  /** Current selection. One-way: these selects apply through `@change`, not `v-model`. */
  modelValue?: string
  class?: HTMLAttributes['class']
}>()
</script>

<template>
  <!--
    The accessible name arrives as a fall-through `aria-label` from the call site (all five
    currently pass one); the rule cannot see across the component boundary. This is a latent risk
    rather than a false alarm — nothing forces a future consumer to pass one — so the stronger fix
    is to make the label a required prop. Left as a follow-up because it changes the API of a
    shared component and every call site, which is more than this change should carry.
  -->
  <span :class="cn('relative inline-flex', props.class)">
    <!-- eslint-disable-next-line vuejs-accessibility/form-control-has-label -->
    <select
      :class="cn(FIELD_CLASS, 'w-full appearance-none pr-8')"
      :value="modelValue"
      v-bind="$attrs"
    >
      <slot />
    </select>
    <ChevronDown
      aria-hidden="true"
      class="pointer-events-none absolute top-1/2 right-2 size-4 -translate-y-1/2 text-muted-foreground"
    />
  </span>
</template>
