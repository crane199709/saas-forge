<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { TenantMembership } from '@crane199709/sdk'

defineProps<{ memberships: TenantMembership[]; busy: boolean }>()
const emit = defineEmits<{ select: [membershipId: string] }>()
const { t } = useI18n()
</script>

<template>
  <section class="tenant-select" :aria-label="t('tenant.selectTitle')">
    <h3>{{ t('tenant.selectTitle') }}</h3>
    <p>{{ t('tenant.selectHint') }}</p>
    <ul>
      <li v-for="membership in memberships" :key="membership.membershipId">
        <el-button
          :data-testid="`tenant-option-${membership.membershipId}`"
          :loading="busy"
          @click="emit('select', membership.membershipId)"
          >{{ membership.tenantName }}</el-button
        >
      </li>
    </ul>
  </section>
</template>

<style scoped>
h3 {
  margin: 0 0 var(--forge-space-2);
  font-size: var(--forge-font-size-md);
  font-weight: 600;
}
p {
  margin: 0 0 var(--forge-space-3);
  color: var(--el-text-color-secondary);
}
ul {
  display: flex;
  flex-direction: column;
  gap: var(--forge-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}
li .el-button {
  width: 100%;
}
</style>
