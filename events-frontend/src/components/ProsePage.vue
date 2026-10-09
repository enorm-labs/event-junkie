<script lang="ts" setup>
/**
 * Shell for the About page, which is Markdown (`views/AboutView.*.md`, #471). The paragraphs,
 * headings and links carry no classes of their own, so the styling they had in the `.vue` version
 * lives here as `[&_x]` selectors, as in {@link LegalPage}.
 */
</script>

<template>
  <main
    class="mx-auto max-w-3xl space-y-6 p-4 text-prose sm:p-8 [&_a]:text-foreground [&_a]:underline [&_a]:underline-offset-4 [&_h1]:text-page [&_h1]:font-bold [&_h1]:tracking-tight [&_h2]:text-section [&_h2]:font-bold [&_h2]:tracking-tight [&_p]:text-muted-foreground [&_section]:space-y-4 [&_section]:pt-4 [&_strong]:font-medium [&_strong]:text-foreground"
  >
    <slot />
  </main>
</template>
