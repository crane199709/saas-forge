<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  groupPermissions,
  type PermissionTree,
  type PermissionMenu,
  type TreePermission,
} from './role-api'
import { menuCodes, menuRows, togglePermissions } from './permission-selection'

const props = defineProps<{ tree: PermissionTree }>()
const selected = defineModel<string[]>({ required: true })
const { t } = useI18n()
const rows = computed(() => menuRows(props.tree.menus))
const groups = computed(() => groupPermissions(props.tree.others))
const names = computed(() => new Map(rows.value.map(({ menu }) => [menu.code, menu.name])))
const codes = (menu: PermissionMenu) =>
  menu.children.length ? menuCodes(menu) : menu.permission ? [menu.permission.code] : []
const checked = (values: string[]) =>
  values.length > 0 && values.every((code) => selected.value.includes(code))
const partial = (values: string[]) =>
  !checked(values) && values.some((code) => selected.value.includes(code))
const change = (values: string[], value: unknown) => {
  selected.value = togglePermissions(selected.value, values, value === true, props.tree.menus)
}
const shared = (permission: TreePermission) =>
  t('role.tree.shared', {
    menus: permission.sharedWith.map((code) => names.value.get(code) ?? code).join('、'),
  })
</script>

<template>
  <div class="permission-tree">
    <div v-if="rows.length" class="section-title">{{ t('role.tree.menus') }}</div>
    <div
      v-for="{ menu, depth } in rows"
      :key="menu.code"
      :style="{ marginLeft: `${depth * 20}px` }"
    >
      <div class="permission-row">
        <el-checkbox
          :model-value="checked(codes(menu))"
          :indeterminate="partial(codes(menu))"
          :disabled="codes(menu).length === 0"
          :data-testid="`menu-${menu.code}`"
          @change="(value) => change(menuCodes(menu), value)"
          >{{ menu.name }}</el-checkbox
        >
        <span v-if="!menu.children.length && !menu.permission" class="hint">{{
          t('role.tree.alwaysVisible')
        }}</span>
        <span v-if="menu.permission?.sharedWith.length" class="hint">{{
          shared(menu.permission)
        }}</span>
      </div>
      <div v-for="action in menu.actions" :key="action.code" class="permission-row action">
        <el-checkbox
          :model-value="selected.includes(action.code)"
          :data-testid="`action-${menu.code}-${action.code}`"
          @change="(value) => change([action.code], value)"
          >{{ action.name }}（{{ action.code }}）</el-checkbox
        >
        <span v-if="action.sharedWith.length" class="hint">{{ shared(action) }}</span>
      </div>
    </div>
    <div v-if="groups.length" class="section-title">{{ t('role.tree.others') }}</div>
    <div v-for="group in groups" :key="group.key" class="permission-group">
      <div class="hint">{{ group.service }} · {{ group.group ?? t('role.dialog.ungrouped') }}</div>
      <el-checkbox
        v-for="permission in group.permissions"
        :key="permission.code"
        :model-value="selected.includes(permission.code)"
        :data-testid="`perm-${permission.code}`"
        @change="(value) => change([permission.code], value)"
        >{{ permission.name }}（{{ permission.code }}）</el-checkbox
      >
    </div>
  </div>
</template>

<style scoped>
.permission-tree {
  width: 100%;
}
.section-title {
  font-weight: 600;
  margin: 8px 0;
}
.permission-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.action {
  margin-left: 20px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.permission-group {
  margin-bottom: 8px;
}
</style>
