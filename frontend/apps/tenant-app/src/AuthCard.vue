<script setup lang="ts">
defineProps<{ title: string }>()
</script>

<template>
  <main class="auth-page">
    <el-card class="auth-card">
      <section :aria-label="title">
        <h2>{{ title }}</h2>
        <slot />
      </section>
    </el-card>
  </main>
</template>

<style scoped>
.auth-page {
  display: flex;
  box-sizing: border-box;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: var(--forge-space-4);
  color: var(--el-text-color-regular);
  font-size: var(--forge-font-size-sm);
  background-color: var(--forge-color-page);
}
.auth-card {
  width: 100%;
  max-width: 420px;
}
h2 {
  margin: 0 0 var(--forge-space-4);
  color: var(--el-text-color-primary);
  font-size: var(--forge-font-size-lg);
  font-weight: 600;
}
</style>
