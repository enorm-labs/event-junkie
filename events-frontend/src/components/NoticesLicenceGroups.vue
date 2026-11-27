<script lang="ts" setup>
/**
 * The components of `/legal/notices`, one collapsed group per licence. Both language versions of the
 * page render it, because the list is generated data with two labels, not prose.
 */
import { useI18n } from 'vue-i18n'
import { useNotices } from '@/composables/useNotices'

const { t } = useI18n()
const { groups, openGroups, toggle, versionSuffix } = useNotices()
</script>

<template>
  <details
    v-for="group in groups"
    :key="group.license"
    class="rounded-lg border border-border p-4"
    @toggle="toggle(group.license, ($event.target as HTMLDetailsElement).open)"
  >
    <summary class="cursor-pointer text-card-title font-medium text-foreground">
      {{ group.license }}
      <span class="font-normal text-muted-foreground">
        — {{ t('legal.notices.componentCount', group.components.length) }}
      </span>
    </summary>

    <p v-if="group.url" class="mt-2 text-body">
      <a :href="group.url" rel="noopener" target="_blank">{{
        t('legal.notices.readLicence', { licence: group.license })
      }}</a>
    </p>

    <!-- Rendered only while open: mounting every row up front is ~640 list items and a visible
         hitch on the first paint of this route. -->
    <ul v-if="openGroups.has(group.license)" class="mt-3 text-body">
      <li v-for="component in group.components" :key="`${component.name}@${component.version}`">
        <a v-if="component.url" :href="component.url" rel="noopener" target="_blank">{{
          component.name
        }}</a>
        <span v-else>{{ component.name }}</span
        ><span class="text-muted-foreground">{{ versionSuffix(component) }}</span>
      </li>
    </ul>
  </details>
</template>
