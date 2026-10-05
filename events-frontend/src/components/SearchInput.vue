<script lang="ts" setup>
/**
 * The search field of every list page and of `/search`: a `BaseInput` with an inline ✕ that
 * empties the text and nothing else (#2692). The browser's own cancel button is hidden, so
 * every engine shows the same one. Attributes fall through to the input.
 */
import { X } from '@lucide/vue'
import { computed, type HTMLAttributes, useTemplateRef } from 'vue'
import { useI18n } from 'vue-i18n'
import BaseInput from '@/components/BaseInput.vue'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

defineOptions({ inheritAttrs: false })

const props = defineProps<{ class?: HTMLAttributes['class'] }>()
const model = defineModel<string>({ required: true })
const emit = defineEmits<{ clear: [] }>()

const { t } = useI18n()
const clearLabel = computed(() => t('common.actions.clearSearch'))
const field = useTemplateRef<InstanceType<typeof BaseInput>>('field')

function clear() {
  model.value = ''
  emit('clear')
  // The button disappears with the text, so focus goes back to where the visitor types.
  ;(field.value?.$el as HTMLInputElement | undefined)?.focus()
}
</script>

<template>
  <div :class="cn('relative', props.class)">
    <BaseInput
      ref="field"
      v-bind="$attrs"
      v-model="model"
      class="w-full ps-3 pe-8 [&::-webkit-search-cancel-button]:appearance-none"
      type="search"
    />
    <Button
      v-if="model"
      :aria-label="clearLabel"
      :title="clearLabel"
      class="absolute top-1 right-1"
      size="icon-xs"
      type="button"
      variant="ghost"
      @click="clear"
    >
      <X aria-hidden="true" />
    </Button>
  </div>
</template>
