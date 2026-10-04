<script setup lang="ts">
/**
 * The registry's `DropdownMenuContent`, ported by hand as `PopoverContent` was: a border instead
 * of its shadow and ring (design rule, #1241), and no enter/exit animation.
 */
import type { DropdownMenuContentEmits, DropdownMenuContentProps } from 'reka-ui'
import { computed, type HTMLAttributes } from 'vue'
import { DropdownMenuContent, DropdownMenuPortal, useForwardPropsEmits } from 'reka-ui'
import { cn } from '@/lib/utils'

defineOptions({
  inheritAttrs: false,
})

const props = withDefaults(
  defineProps<DropdownMenuContentProps & { class?: HTMLAttributes['class'] }>(),
  {
    sideOffset: 4,
  },
)
const emits = defineEmits<DropdownMenuContentEmits>()

const delegatedProps = computed(() => {
  const delegated = { ...props }
  delete delegated.class
  return delegated
})

const forwarded = useForwardPropsEmits(delegatedProps, emits)
</script>

<template>
  <DropdownMenuPortal>
    <DropdownMenuContent
      data-slot="dropdown-menu-content"
      v-bind="{ ...$attrs, ...forwarded }"
      :class="
        cn(
          'z-50 max-h-(--reka-dropdown-menu-content-available-height) min-w-40 origin-(--reka-dropdown-menu-content-transform-origin) overflow-y-auto rounded-lg border border-border bg-popover p-1 text-popover-foreground outline-hidden',
          props.class,
        )
      "
    >
      <slot />
    </DropdownMenuContent>
  </DropdownMenuPortal>
</template>
