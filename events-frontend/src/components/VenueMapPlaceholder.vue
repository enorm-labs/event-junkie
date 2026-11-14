<!-- VenueMap's own box, held while its chunk loads, so the content under the map does not jump (#5026). -->
<script lang="ts" setup>
// The async wrapper hands the map's props down to this box; none of them belong on a div.
defineOptions({ inheritAttrs: false })
</script>

<template>
  <div
    aria-hidden="true"
    class="-mx-4 h-112 border-y border-border sm:mx-0 sm:rounded-lg sm:border-x lg:h-144"
  />
</template>
