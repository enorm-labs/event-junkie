<script setup lang="ts">
import { ChevronLeft, ChevronRight } from '@lucide/vue'

import { Button } from '@/components/ui/button'

// Knows nothing of pages or offsets: the worklist pages by offset, the other views by page.
defineProps<{ hasPrevious: boolean; hasNext: boolean; loading: boolean }>()

const emit = defineEmits<{ previous: []; next: [] }>()
</script>

<template>
  <Button
    :disabled="!hasPrevious || loading"
    aria-label="Previous page"
    size="icon-sm"
    type="button"
    variant="outline"
    @click="emit('previous')"
  >
    <ChevronLeft />
  </Button>
  <Button
    :disabled="!hasNext || loading"
    aria-label="Next page"
    size="icon-sm"
    type="button"
    variant="outline"
    @click="emit('next')"
  >
    <ChevronRight />
  </Button>
</template>
