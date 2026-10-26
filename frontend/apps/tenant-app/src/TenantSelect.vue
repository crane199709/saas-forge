<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { TenantMembership } from '@forge/sdk'

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
ul {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 0;
  list-style: none;
}
</style>
