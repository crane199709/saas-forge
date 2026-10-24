<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { ref } from 'vue'
import { ElButton, ElDialog, ElOption, ElSelect, ElTooltip } from 'element-plus'

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
    },
  },
})
</script>

<template>
  <h3>{{ t('overview') }}</h3>
  <router-link :to="{ name: 'demo:details', params: { id: '42' } }">{{ t('detail') }}</router-link>
  <p>
    <el-button @click="open = true">{{ t('open') }}</el-button>
  </p>
  <el-dialog v-model="open" :title="t('dialog')" width="min(520px, 90vw)" append-to-body>
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
