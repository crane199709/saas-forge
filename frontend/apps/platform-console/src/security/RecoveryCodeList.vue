<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'

// 服务端只存哈希，这组恢复码离开此页后再也取不回，因此只提供复制与下载，不做任何缓存。
const props = defineProps<{ codes: string[] }>()
const emit = defineEmits<{ done: [] }>()
const { t } = useI18n()
const copied = ref(false)

async function copy() {
  await navigator.clipboard.writeText(props.codes.join('\n'))
  copied.value = true
}

function download() {
  const url = URL.createObjectURL(
    new Blob([props.codes.join('\n') + '\n'], { type: 'text/plain;charset=utf-8' }),
  )
  const link = document.createElement('a')
  link.href = url
  link.download = t('recoveryCodes.fileName')
  link.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <section :aria-label="t('recoveryCodes.title')">
    <h3>{{ t('recoveryCodes.title') }}</h3>
    <el-alert type="warning" :closable="false" :title="t('recoveryCodes.hint')" />
    <ol class="codes" data-testid="recovery-codes">
      <li v-for="code in codes" :key="code">
        <code>{{ code }}</code>
      </li>
    </ol>
    <div class="actions">
      <el-button data-testid="recovery-codes-copy" @click="copy">{{
        copied ? t('recoveryCodes.copied') : t('recoveryCodes.copy')
      }}</el-button>
      <el-button data-testid="recovery-codes-download" @click="download">{{
        t('recoveryCodes.download')
      }}</el-button>
      <el-button type="primary" data-testid="recovery-codes-done" @click="emit('done')">{{
        t('recoveryCodes.done')
      }}</el-button>
    </div>
  </section>
</template>

<style scoped>
.codes {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  padding-left: 24px;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
