<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { ref } from 'vue'
import { ElButton, ElDialog, ElOption, ElSelect, ElTooltip } from 'element-plus'
import { useTenantClient } from '@crane199709/sdk'
import { overlaySize } from '@crane199709/ui'
import OrdersList from './OrdersList.vue'

// Token 与租户上下文经共享的 @crane199709/sdk 获取，Sub-App 自身不处理登录
const client = useTenantClient()

const open = ref(false)
const selected = ref('')
const { t } = useI18n({
  useScope: 'local',
  messages: {
    'zh-CN': {
      overview: '概览',
      detail: '查看详情',
      open: '打开弹窗',
      dialog: '子应用弹窗',
      select: '选择颜色',
      blue: '蓝色',
      selected: '已选择：{color}',
      tooltipButton: '提示说明',
      tooltip: '共享样式的提示',
      close: '关闭',
      createOrder: '新建订单',
      tenant: '当前租户：{name}',
    },
  },
})
</script>

<template>
  <div class="section-head">
    <h3>{{ t('overview') }}</h3>
    <div class="actions">
      <router-link class="link" :to="{ name: 'demo:details', params: { id: '42' } }">{{
        t('detail')
      }}</router-link>
      <el-button @click="open = true">{{ t('open') }}</el-button>
      <!-- 按钮级权限由 Shell 安装的 v-perm 控制；接口仍由 demo-service 按 order:create 校验 -->
      <el-button v-perm="'order:create'" type="primary" data-testid="create-order">{{
        t('createOrder')
      }}</el-button>
    </div>
  </div>
  <p data-testid="remote-tenant">
    {{ t('tenant', { name: client.session()?.tenantName ?? '-' }) }}
  </p>
  <OrdersList />
  <el-dialog v-model="open" :title="t('dialog')" :width="overlaySize.dialogSm" append-to-body>
    <el-select v-model="selected" :aria-label="t('select')" :placeholder="t('select')">
      <el-option :label="t('blue')" value="blue" />
    </el-select>
    <p v-if="selected">{{ t('selected', { color: t(selected) }) }}</p>
    <p>
      <el-tooltip :content="t('tooltip')" placement="top"
        ><el-button>{{ t('tooltipButton') }}</el-button></el-tooltip
      >
    </p>
    <template #footer
      ><el-button @click="open = false">{{ t('close') }}</el-button></template
    >
  </el-dialog>
</template>

<style scoped>
.section-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--forge-space-2);
}
h3 {
  margin: 0;
  font-size: var(--forge-font-size-md);
  font-weight: 600;
}
.actions {
  display: flex;
  align-items: center;
  gap: var(--forge-space-3);
}
.link {
  color: var(--el-color-primary);
  text-decoration: none;
}
p {
  margin: var(--forge-space-2) 0 var(--forge-space-5);
}
</style>
