<script lang="ts" setup>
/**
 * A line saying something failed: a page that did not load, or an action that did not work. An
 * action's line passes `role="alert"`, so a screen reader announces it; both fall through to the `p`.
 */
</script>

<template>
  <p class="text-body text-destructive"><slot /></p>
</template>
