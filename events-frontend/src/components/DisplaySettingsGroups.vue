<script lang="ts" setup>
/**
 * The theme and the events view as two pressed-state groups (#2568). The header's popover shows
 * them, and so does the phone's menu sheet when the bell takes the button's place (#4078). Each
 * mount reads the stored theme, so the two places never disagree.
 */
import { computed, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { useCompactView } from '@/composables/useCompactView'
import { saveTheme, storedTheme, type Theme } from '@/lib/theme'

const { t } = useI18n()

const theme = ref<Theme>(storedTheme())

function setTheme(value: Theme) {
  if (value === theme.value) return
  theme.value = value
  saveTheme(value)
}

const { compact, toggle: toggleCompact } = useCompactView()

function setCompact(value: boolean) {
  if (value !== compact.value) toggleCompact()
}

const themeId = useId()
const viewId = useId()

const groups = computed(() => [
  {
    id: themeId,
    label: t('common.display.theme'),
    options: [
      {
        label: t('common.display.light'),
        pressed: theme.value === 'light',
        pick: () => setTheme('light'),
      },
      {
        label: t('common.display.dark'),
        pressed: theme.value === 'dark',
        pick: () => setTheme('dark'),
      },
      {
        label: t('common.display.system'),
        pressed: theme.value === 'system',
        pick: () => setTheme('system'),
      },
    ],
  },
  {
    id: viewId,
    label: t('common.display.events'),
    options: [
      {
        label: t('common.display.posters'),
        pressed: !compact.value,
        pick: () => setCompact(false),
      },
      { label: t('common.display.compact'), pressed: compact.value, pick: () => setCompact(true) },
    ],
  },
])
</script>

<template>
  <div class="flex flex-col gap-2.5">
    <div v-for="group in groups" :key="group.id" class="flex flex-col gap-1.5">
      <span :id="group.id" class="text-meta text-muted-foreground">{{ group.label }}</span>
      <div :aria-labelledby="group.id" class="flex gap-1" role="group">
        <Button
          v-for="option in group.options"
          :key="option.label"
          :aria-pressed="option.pressed"
          :variant="option.pressed ? 'default' : 'outline'"
          class="flex-1"
          size="sm"
          type="button"
          @click="option.pick"
        >
          {{ option.label }}
        </Button>
      </div>
    </div>
  </div>
</template>
