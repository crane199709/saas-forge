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
  justify-content: center;
  padding: 80px 16px;
}
.auth-card {
  width: 100%;
  max-width: 480px;
}
</style>
