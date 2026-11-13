<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { errorTip } from './error-tip'
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useAccessControl, useTenantClient } from '@crane199709/sdk'
import { ErrorAlert, PageHeader, formatDateTime, type ErrorTip } from '@crane199709/ui'
import {
  createNotificationApi,
  type InboxItem,
  type InboxTab,
} from './notification/notification-api'

const { t } = useI18n()
const client = useTenantClient()
const api = createNotificationApi(client)
const access = useAccessControl()
const session = ref(client.session())
const definitions: { kind: InboxTab; permission: string; title: string; empty: string }[] = [
  { kind: 'todos', permission: 'todo:read', title: '我的待办', empty: '暂无待办' },
  {
    kind: 'notifications',
    permission: 'notification:read',
    title: '未读通知',
    empty: '暂无未读通知',
  },
  { kind: 'announcements', permission: 'announcement:read', title: '最新公告', empty: '暂无公告' },
]
const cards = reactive(
  definitions.map((definition) => ({
    ...definition,
    items: [] as InboxItem[],
    count: null as number | null,
    loading: false,
    error: null as ErrorTip | null,
  })),
)
const visibleCards = computed(() => cards.filter((card) => access.has(card.permission)))
const busy = computed(() => visibleCards.value.some((card) => card.loading))
let generation = 0
async function unreadNotifications(started: number) {
  const items: InboxItem[] = []
  let number = 1
  while (items.length < 5 && started === generation) {
    const result = await api.notifications(number)
    if (started !== generation) return []
    items.push(...result.items.filter((item) => !item.read))
    if (number >= result.page.totalPages) break
    number += 1
  }
  return items.slice(0, 5)
}
async function loadCard(card: (typeof cards)[number], started: number) {
  card.loading = true
  try {
    let items: InboxItem[]
    let count: number | null = null
    if (card.kind === 'todos') items = (await api.todos(1, 5)).items.slice(0, 5)
    else if (card.kind === 'announcements') items = (await api.announcements()).slice(0, 3)
    else {
      const result = await Promise.all([unreadNotifications(started), api.unreadCount()])
      items = result[0]
      count = result[1].count
    }
    if (started !== generation) return
    card.items = items
    card.count = count
  } catch (cause) {
    if (started === generation) card.error = errorTip(cause, t)
  } finally {
    if (started === generation) card.loading = false
  }
}
function refresh() {
  const started = ++generation
  for (const card of cards) {
    card.items = []
    card.count = null
    card.error = null
    card.loading = false
  }
  if (session.value) {
    for (const card of visibleCards.value) void loadCard(card, started)
  }
}
function target(kind: InboxTab, item: InboxItem) {
  if (kind === 'notifications') return '/notifications'
  if (kind === 'announcements')
    return { path: '/notifications', query: { tab: kind, item: item.id } }
  const link =
    item.link ?? (item.subApp && item.route ? { appCode: item.subApp, route: item.route } : null)
  return link ? `/apps/${link.appCode}${link.route}` : null
}
onMounted(refresh)
const unsubscribe = client.onSessionChange((value) => {
  const changed =
    session.value?.membershipId !== value?.membershipId ||
    session.value?.tenantId !== value?.tenantId
  session.value = value
  if (changed) refresh()
})
onUnmounted(() => {
  generation += 1
  unsubscribe()
})
</script>

<template>
  <section class="workbench" aria-label="工作台">
    <PageHeader title="工作台">
      <template #actions>
        <el-button
          v-if="visibleCards.length"
          :disabled="busy"
          data-testid="workbench-refresh"
          @click="refresh"
          >刷新工作台</el-button
        >
      </template>
    </PageHeader>
    <p v-if="session" class="greeting" data-testid="workbench-greeting">
      {{ session.identityName }} · {{ session.tenantName }}
    </p>
    <div class="workbench-cards">
      <section
        v-for="card in visibleCards"
        :key="card.kind"
        class="workbench-card"
        :aria-label="card.title"
        :data-testid="`workbench-${card.kind}`"
      >
        <header class="card-header">
          <h3>{{ card.title }}</h3>
          <el-tag
            v-if="card.kind === 'notifications' && card.count !== null"
            :aria-label="`${card.count} 条未读通知`"
            size="small"
            >{{ card.count }}</el-tag
          >
        </header>
        <p v-if="card.loading" class="placeholder" role="status">加载中…</p>
        <ErrorAlert v-else-if="card.error" :tip="card.error" />
        <p v-else-if="!card.items.length" class="placeholder">{{ card.empty }}</p>
        <ul v-else class="workbench-list">
          <li v-for="item in card.items" :key="item.id" class="workbench-item">
            <router-link
              v-if="target(card.kind, item)"
              class="workbench-item-title"
              :to="target(card.kind, item)!"
              >{{ item.title }}</router-link
            >
            <span v-else class="workbench-item-title">{{ item.title }}</span>
            <p class="item-body">{{ item.body }}</p>
            <time>{{ formatDateTime(item.occurredAt ?? item.publishedAt) }}</time>
          </li>
        </ul>
      </section>
    </div>
  </section>
</template>

<style scoped>
.greeting {
  margin: 0 0 var(--forge-space-4);
  color: var(--el-text-color-regular);
  overflow-wrap: anywhere;
}
.workbench-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--forge-space-4);
  align-items: start;
}
.workbench-card {
  min-width: 0;
  padding: var(--forge-space-4);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--forge-radius-lg);
}
.card-header {
  display: flex;
  align-items: center;
  gap: var(--forge-space-2);
  margin-bottom: var(--forge-space-3);
}
h3 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: var(--forge-font-size-md);
  font-weight: 600;
}
.placeholder {
  margin: 0;
  padding: var(--forge-space-5) 0;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.workbench-list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.workbench-item {
  padding: var(--forge-space-3) 0;
  border-top: 1px solid var(--el-border-color-lighter);
}
.workbench-item-title {
  display: block;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: var(--forge-font-size-sm);
  text-decoration: none;
  text-overflow: ellipsis;
  white-space: nowrap;
}
a.workbench-item-title:hover {
  color: var(--el-color-primary);
}
.item-body {
  margin: var(--forge-space-1) 0;
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: var(--forge-font-size-sm);
  text-overflow: ellipsis;
  white-space: nowrap;
}
time {
  color: var(--el-text-color-secondary);
  font-size: var(--forge-font-size-xs);
}
@media (max-width: 900px) {
  .workbench-cards {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 640px) {
  :deep(.page-header) {
    flex-direction: column;
    gap: var(--forge-space-2) !important;
  }
}
</style>
