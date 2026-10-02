<script lang="ts" setup>
import { useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'

export interface SortOption {
  /** The `sort` query value, or `''` for an order that is the API's default. */
  value: string
  label: string
}

/**
 * The one sort control every list shares: a visible "Sort" label and a button per order. Two or
 * three orders read faster as buttons than behind a select, and the pressed one names the order
 * on screen (#360).
 */
defineProps<{
  options: SortOption[]
  modelValue: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const labelId = useId()
const { t } = useI18n()
</script>

<template>
  <div class="flex items-center gap-2">
    <span :id="labelId" class="text-body text-muted-foreground">{{ t('common.sort.label') }}</span>
    <div :aria-labelledby="labelId" class="flex gap-1" role="group">
      <Button
        v-for="option in options"
        :key="option.value"
        :aria-pressed="option.value === modelValue"
        :variant="option.value === modelValue ? 'default' : 'outline'"
        size="sm"
        type="button"
        @click="option.value !== modelValue && emit('update:modelValue', option.value)"
      >
        {{ option.label }}
      </Button>
    </div>
  </div>
</template>
