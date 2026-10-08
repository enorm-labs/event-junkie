<script setup lang="ts">
/**
 * The registry's `SheetContent`, ported by hand as `DropdownMenuContent` was: the bottom side only,
 * a hairline instead of its shadow (#1241), and no enter/exit animation.
 */
import type { DialogContentEmits, DialogContentProps } from 'reka-ui'
import { computed, type HTMLAttributes } from 'vue'
import { DialogContent, DialogOverlay, DialogPortal, useForwardPropsEmits } from 'reka-ui'
import { cn } from '@/lib/utils'

defineOptions({ inheritAttrs: false })

const props = defineProps<DialogContentProps & { class?: HTMLAttributes['class'] }>()
const emits = defineEmits<DialogContentEmits>()

const delegatedProps = computed(() => {
  const delegated = { ...props }
  delete delegated.class
  return delegated
})

const forwarded = useForwardPropsEmits(delegatedProps, emits)
</script>

<template>
  <DialogPortal>
    <DialogOverlay data-slot="sheet-overlay" class="fixed inset-0 z-50 bg-background/80" />
    <DialogContent
      data-slot="sheet-content"
      v-bind="{ ...$attrs, ...forwarded }"
      :class="
        cn(
          'fixed inset-x-0 bottom-0 z-50 flex max-h-[85dvh] flex-col rounded-t-xl border-t border-border bg-background text-foreground outline-hidden',
          props.class,
        )
      "
    >
      <slot />
    </DialogContent>
  </DialogPortal>
</template>
