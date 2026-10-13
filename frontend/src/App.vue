<template>
  <div class="app-root">
    <router-view />
  </div>
</template>

<script setup>
// Root application container for PDF Editor
</script>

<style scoped>
.app-root {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background-color: var(--color-bg-workspace);
}
</style>
