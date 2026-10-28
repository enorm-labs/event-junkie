<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { cn } from '@/lib/utils'

const props = defineProps<{ class?: HTMLAttributes['class'] }>()
</script>

<template>
  <!-- Overlapping 1 px edges, so a pressed or focused button's border shows on both sides. -->
  <div
    :class="
      cn(
        'flex w-fit items-stretch *:focus-visible:z-10 *:aria-pressed:z-10 [&>*:not(:first-child)]:-ml-px [&>*:not(:first-child)]:rounded-l-none [&>*:not(:last-child)]:rounded-r-none',
        props.class,
      )
    "
    data-slot="button-group"
    role="group"
  >
    <slot />
  </div>
</template>
