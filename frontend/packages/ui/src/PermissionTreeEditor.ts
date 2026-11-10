import { ElCheckbox } from 'element-plus'
import { computed, defineComponent, h, type PropType, type VNode } from 'vue'
import { useUiI18n } from './i18n'
import {
  groupPermissions,
  menuCodes,
  menuRows,
  togglePermissions,
  type PermissionMenu,
  type PermissionSection,
  type PermissionTree,
  type TreePermission,
} from './permission-selection'

const sectionTitle = (text: string) =>
  h(
    'div',
    { style: { margin: 'var(--forge-space-2) 0', color: 'var(--el-text-color-regular)' } },
    text,
  )

const hint = (text: string) =>
  h(
    'span',
    {
      style: {
        color: 'var(--el-text-color-secondary)',
        fontSize: 'var(--forge-font-size-xs)',
      },
    },
    text,
  )

const row = (children: VNode[], indent?: string) =>
  h(
    'div',
    {
      style: {
        display: 'flex',
        flexWrap: 'wrap',
        alignItems: 'center',
        gap: 'var(--forge-space-2)',
        marginLeft: indent,
      },
    },
    children,
  )

/**
 * 菜单授权树：按菜单页面勾选 Permission Code，未挂在菜单上的权限码列在「功能权限」区，调用方可追加分区。
 *
 * 勾选页面即勾选页面权限及全部操作，操作可逐项取消；勾选操作时自动勾上页面权限，取消页面权限时其下操作一并取消；
 * 多个页面共用的码同步勾选并提示。不挂权限码的页面始终可见，页面本身不可勾选。
 */
export const PermissionTreeEditor = defineComponent({
  name: 'PermissionTreeEditor',
  props: {
    tree: { type: Object as PropType<PermissionTree>, required: true },
    /** 已勾选的权限码，配合 `v-model` 使用。 */
    modelValue: { type: Array as PropType<string[]>, required: true },
    /**
     * 页面权限作为子项：页面节点只负责全选和半选，页面权限显示为第一个子项。
     * 默认 false，页面节点的勾选框即代表页面权限。
     */
    pageAsChild: Boolean,
    /** 显示在「功能权限」区之后的分区。 */
    sections: { type: Array as PropType<PermissionSection[]>, default: () => [] },
  },
  emits: {
    'update:modelValue': (codes: string[]) => Array.isArray(codes),
  },
  setup(props, { emit }) {
    const { t } = useUiI18n()
    const rows = computed(() => menuRows(props.tree.menus))
    const groups = computed(() => groupPermissions(props.tree.others))
    const names = computed(() => new Map(rows.value.map(({ menu }) => [menu.code, menu.name])))
    const selected = (code: string) => props.modelValue.includes(code)
    // 节点勾选框代表的码：分组为其下全部码；页面在默认方式下只代表页面权限，子项方式下代表页面权限及操作。
    const codes = (menu: PermissionMenu) => {
      if (menu.children.length) return menuCodes(menu)
      if (!menu.permission) return []
      return props.pageAsChild ? menuCodes(menu) : [menu.permission.code]
    }
    const checked = (values: string[]) => values.length > 0 && values.every(selected)
    const partial = (values: string[]) => !checked(values) && values.some(selected)
    const change = (values: string[], value: unknown) =>
      emit(
        'update:modelValue',
        togglePermissions(props.modelValue, values, value === true, props.tree.menus),
      )
    const shared = (permission: TreePermission) =>
      permission.sharedWith.length
        ? [
            hint(
              t('permissionTree.shared', {
                menus: permission.sharedWith
                  .map((code) => names.value.get(code) ?? code)
                  .join('、'),
              }),
            ),
          ]
        : []
    const option = (permission: TreePermission, testid: string, onChange: (v: unknown) => void) =>
      row(
        [
          h(
            ElCheckbox,
            {
              modelValue: selected(permission.code),
              'data-testid': testid,
              onChange,
            },
            () => `${permission.name}（${permission.code}）`,
          ),
          ...shared(permission),
        ],
        'var(--forge-space-5)',
      )

    const menuRow = (menu: PermissionMenu, depth: number) => {
      const values = codes(menu)
      const leaf = !menu.children.length
      return h('div', { key: menu.code, style: { marginLeft: `${depth * 20}px` } }, [
        row([
          h(
            ElCheckbox,
            {
              modelValue: checked(values),
              indeterminate: partial(values),
              disabled: values.length === 0,
              'data-testid': `menu-${menu.code}`,
              onChange: (value: unknown) => change(menuCodes(menu), value),
            },
            () => menu.name,
          ),
          ...(menu.note ? [hint(menu.note)] : []),
          ...(leaf && !menu.permission ? [hint(t('permissionTree.alwaysVisible'))] : []),
          ...(!props.pageAsChild && menu.permission ? shared(menu.permission) : []),
        ]),
        ...(props.pageAsChild && menu.permission
          ? [
              option(menu.permission, `page-${menu.code}`, (value) =>
                change([menu.permission!.code], value),
              ),
            ]
          : []),
        ...menu.actions.map((action) =>
          option(action, `action-${menu.code}-${action.code}`, (value) =>
            change([action.code], value),
          ),
        ),
      ])
    }

    const flat = (
      key: string,
      title: string | null,
      permissions: { code: string; name: string }[],
    ) =>
      h('div', { key, style: { marginBottom: 'var(--forge-space-2)' } }, [
        ...(title ? [h('div', null, [hint(title)])] : []),
        ...permissions.map((permission) =>
          h(
            ElCheckbox,
            {
              key: permission.code,
              modelValue: selected(permission.code),
              'data-testid': `perm-${permission.code}`,
              onChange: (value: unknown) => change([permission.code], value),
            },
            () => `${permission.name}（${permission.code}）`,
          ),
        ),
      ])

    return () =>
      h('div', { class: 'permission-tree', style: { width: '100%' } }, [
        ...(rows.value.length ? [sectionTitle(t('permissionTree.menus'))] : []),
        ...rows.value.map(({ menu, depth }) => menuRow(menu, depth)),
        ...(groups.value.length ? [sectionTitle(t('permissionTree.others'))] : []),
        ...groups.value.map((group) =>
          flat(group.key, group.group ?? t('permissionTree.ungrouped'), group.permissions),
        ),
        ...props.sections
          .filter((section) => section.permissions.length)
          .flatMap((section) => [
            sectionTitle(section.title),
            flat(section.key, null, section.permissions),
          ]),
      ])
  },
})
