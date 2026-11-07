<script setup lang="ts">
import { useInboxUnread } from './unread-count'
const { count, failed } = useInboxUnread()
</script>
<template>
  <router-link
    to="/notifications"
    class="notification-bell"
    data-testid="notification-bell"
    :aria-label="count === null ? '通知中心' : `通知中心，${count} 条未读`"
    :title="failed ? '未读数量加载失败，请进入通知中心重试' : '通知中心'"
  >
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.8"
      aria-hidden="true"
    >
      <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" />
    </svg>
    <span v-if="count" class="unread-count">{{ count > 99 ? '99+' : count }}</span>
  </router-link>
</template>
<style scoped>
.notification-bell {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--el-text-color-primary);
  text-decoration: none;
  padding: 8px;
}
.unread-count {
  border-radius: 12px;
  padding: 1px 6px;
  font-size: 12px;
  background: var(--el-color-danger);
  color: white;
}
</style>
