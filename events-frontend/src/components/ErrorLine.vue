<script lang="ts" setup>
/**
 * A line saying something failed: a page that did not load, or an action that did not work. Both
 * are an `alert`, so a screen reader announces the line when it appears.
 */
</script>

<template>
  <p class="text-body text-destructive" role="alert"><slot /></p>
</template>
