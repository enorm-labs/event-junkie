<script lang="ts" setup>
import { Button } from '@/components/ui/button'

// A toggle reads as one control in two states: `pressed` sets the ARIA state and the look together.
defineProps<{ pressed: boolean }>()
</script>

<template>
  <Button :aria-pressed="pressed" :variant="pressed ? 'default' : 'outline'" type="button">
    <slot />
  </Button>
</template>
