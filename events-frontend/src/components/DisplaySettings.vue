<script lang="ts" setup>
/**
 * The header's display preferences (#2568): one icon button at every width, opening the theme and
 * the events view as two pressed-state pairs. Both stay persisted and applied before paint by the
 * inline script in index.html; only the place they are switched from is here.
 */
import { computed, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { Settings2 } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { useCompactView } from '@/composables/useCompactView'

const { t } = useI18n()

// Mirrors the class the pre-paint script set. Lives in the app shell, so it persists across routes.
const THEME_KEY = 'theme'
const isDark = ref<boolean>(document.documentElement.classList.contains('dark'))

function setDark(dark: boolean) {
  if (dark === isDark.value) return
  isDark.value = dark
  document.documentElement.classList.toggle('dark', dark)
  try {
    localStorage.setItem(THEME_KEY, dark ? 'dark' : 'light')
  } catch {
    // Ignore storage failures (e.g. private mode); persistence is best-effort.
  }
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
      { label: t('common.display.light'), pressed: !isDark.value, pick: () => setDark(false) },
      { label: t('common.display.dark'), pressed: isDark.value, pick: () => setDark(true) },
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
  <Popover>
    <PopoverTrigger as-child>
      <Button
        :aria-label="t('common.display.label')"
        :title="t('common.display.label')"
        size="icon"
        variant="outline"
      >
        <Settings2 />
      </Button>
    </PopoverTrigger>
    <PopoverContent align="end" class="w-auto">
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
    </PopoverContent>
  </Popover>
</template>
