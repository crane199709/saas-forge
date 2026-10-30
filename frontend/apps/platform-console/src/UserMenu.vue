<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { signOut } from './login-flow'
import { usePlatformClient } from './shell'

const { t } = useI18n()
const client = usePlatformClient()
const session = ref(client.session())
onUnmounted(
  client.onSessionChange((value) => {
    session.value = value
  }),
)

// 会话清除后路由守卫离开当前页面回到登录页，IAM 退出失败由登录页提示。
function command(name: string) {
  if (name === 'logout') void signOut(client)
}
</script>

<template>
  <el-dropdown v-if="session" trigger="click" @command="command">
    <el-button link data-testid="user-menu">{{ session.operatorName }}</el-button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item command="logout" data-testid="user-logout">{{
          t('auth.logout')
        }}</el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>
