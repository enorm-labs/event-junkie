<script setup lang="ts">
/**
 * The registry's `PopoverContent`, ported by hand: a border instead of its shadow and ring
 * (design rule, #1241), and no enter/exit animation, which needs `tw-animate-css`.
 */
import type { PopoverContentEmits, PopoverContentProps } from 'reka-ui'
import { computed, type HTMLAttributes } from 'vue'
import { PopoverContent, PopoverPortal, useForwardPropsEmits } from 'reka-ui'
import { cn } from '@/lib/utils'

defineOptions({
  inheritAttrs: false,
})

const props = withDefaults(
  defineProps<PopoverContentProps & { class?: HTMLAttributes['class'] }>(),
  {
    align: 'center',
    sideOffset: 4,
  },
)
const emits = defineEmits<PopoverContentEmits>()

const delegatedProps = computed(() => {
  const delegated = { ...props }
  delete delegated.class
  return delegated
})

const forwarded = useForwardPropsEmits(delegatedProps, emits)
</script>

<template>
  <PopoverPortal>
    <PopoverContent
      data-slot="popover-content"
      v-bind="{ ...$attrs, ...forwarded }"
      :class="
        cn(
          'z-50 flex w-72 max-w-(--reka-popover-content-available-width) origin-(--reka-popover-content-transform-origin) flex-col gap-2.5 rounded-lg border border-border bg-popover p-2.5 text-sm text-popover-foreground outline-hidden',
          props.class,
        )
      "
    >
      <slot />
    </PopoverContent>
  </PopoverPortal>
</template>
