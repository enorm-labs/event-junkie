<script lang="ts" setup>
// Mono, uppercase "eyebrow" label for section headings — the editorial listings look from
// the brand prototype (docs/BRANDING.md §5.6). Renders an <h2> by default; override `as`
// to keep the right heading level (or use a non-heading element) in a given context.
withDefaults(defineProps<{ as?: string }>(), { as: 'h2' })
</script>

<template>
  <component
    :is="as"
    class="font-mono text-body font-medium tracking-eyebrow text-primary uppercase"
  >
    <slot />
  </component>
</template>
