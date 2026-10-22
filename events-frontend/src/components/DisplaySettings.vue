<script lang="ts" setup>
/**
 * The header's display preferences (#2568): one icon button at every width, opening the theme and
 * the events view as two pressed-state groups. Both stay persisted and applied before paint by the
 * inline script in index.html; only the place they are switched from is here.
 */
import { computed, onBeforeUnmount, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { Settings2 } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { useCompactView } from '@/composables/useCompactView'

const { t } = useI18n()

type Theme = 'light' | 'dark' | 'system'

// Mirrors what the pre-paint script read. Lives in the app shell, so it persists across routes.
// System stores nothing (#3715): no key means "follow the OS", which is also the first-visit default.
const THEME_KEY = 'theme'

function storedTheme(): Theme {
  try {
    const value = localStorage.getItem(THEME_KEY)
    return value === 'light' || value === 'dark' ? value : 'system'
  } catch {
    return 'system'
  }
}

const theme = ref<Theme>(storedTheme())
// jsdom has no `matchMedia`; there the OS counts as light.
const osDark = window.matchMedia?.('(prefers-color-scheme: dark)')

function apply() {
  const dark = theme.value === 'system' ? (osDark?.matches ?? false) : theme.value === 'dark'
  document.documentElement.classList.toggle('dark', dark)
}

function setTheme(value: Theme) {
  if (value === theme.value) return
  theme.value = value
  apply()
  try {
    if (value === 'system') localStorage.removeItem(THEME_KEY)
    else localStorage.setItem(THEME_KEY, value)
  } catch {
    // Ignore storage failures (e.g. private mode); persistence is best-effort.
  }
}

const onOsChange = () => {
  if (theme.value === 'system') apply()
}
osDark?.addEventListener('change', onOsChange)
onBeforeUnmount(() => osDark?.removeEventListener('change', onOsChange))

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
