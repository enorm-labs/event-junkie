<script setup lang="ts">
import type { DialogTitleProps } from 'reka-ui'
import { computed, type HTMLAttributes } from 'vue'
import { DialogTitle } from 'reka-ui'
import { cn } from '@/lib/utils'

const props = defineProps<DialogTitleProps & { class?: HTMLAttributes['class'] }>()

const delegatedProps = computed(() => {
  const delegated = { ...props }
  delete delegated.class
  return delegated
})
</script>

<template>
  <DialogTitle
    data-slot="sheet-title"
    v-bind="delegatedProps"
    :class="cn('text-lede font-semibold', props.class)"
  >
    <slot />
  </DialogTitle>
</template>
