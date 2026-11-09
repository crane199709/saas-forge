import {
  ElAside,
  ElContainer,
  ElHeader,
  ElMain,
  ElMenu,
  ElMenuItem,
  ElMenuItemGroup,
} from 'element-plus'
import { computed, defineComponent, h, resolveComponent, type PropType } from 'vue'

/** 侧边菜单项：带 `path` 的是链接，带 `children` 的是以 `title` 为标题的分组。 */
export interface MenuItem {
  /** 同级唯一；链接带 `data-testid="menu-<key>"`。 */
  key: string
  title: string
  path?: string
  children?: MenuItem[]
}

const linkStyle = { display: 'block', width: '100%', color: 'inherit', textDecoration: 'none' }

function paths(items: MenuItem[]): string[] {
  return items.flatMap((item) => [...(item.path ? [item.path] : []), ...paths(item.children ?? [])])
}

/**
 * 管理后台布局：顶栏（产品名链接到 `/`，右侧为 `actions` 插槽）+ 侧边菜单 + 内容区（默认插槽）。
 * 链接用宿主应用注册的 RouterLink 渲染，本包不依赖 vue-router。
 */
export const AppLayout = defineComponent({
  name: 'AppLayout',
  props: {
    /** 顶栏左侧的产品名。 */
    title: { type: String, required: true },
    /** 侧边菜单，按数组顺序展示；`activePath` 在其中所有 `path` 里取最长匹配。 */
    menu: { type: Array as PropType<MenuItem[]>, required: true },
    /** 当前路由路径；高亮与之最长匹配的菜单项，页内的下级路径也归到该项。 */
    activePath: { type: String, required: true },
  },
  setup(props, { slots }) {
    const active = computed(() =>
      paths(props.menu)
        .filter((path) => props.activePath === path || props.activePath.startsWith(`${path}/`))
        .reduce((best, path) => (path.length > best.length ? path : best), ''),
    )
    return () => {
      const RouterLink = resolveComponent('RouterLink')
      const renderItem = (item: MenuItem): ReturnType<typeof h> =>
        item.children
          ? h(ElMenuItemGroup, { key: item.key, title: item.title }, () =>
              item.children!.map(renderItem),
            )
          : h(
              ElMenuItem,
              {
                key: item.key,
                index: item.path ?? '',
                style: {
                  margin: 'var(--forge-space-1) var(--forge-space-2) 0',
                  borderRadius: 'var(--forge-radius-md)',
                  backgroundColor:
                    item.path === active.value ? 'var(--el-color-primary-light-9)' : undefined,
                },
              },
              () =>
                h(
                  RouterLink,
                  {
                    to: item.path,
                    class: 'menu-link',
                    style: linkStyle,
                    'data-testid': `menu-${item.key}`,
                  },
                  () => item.title,
                ),
            )
      return h(ElContainer, { class: 'shell', style: { minHeight: '100vh' } }, () => [
        h(
          ElHeader,
          {
            class: 'shell-header',
            height: '56px',
            style: {
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              borderBottom: '1px solid var(--el-border-color-light)',
            },
          },
          () => [
            h(
              RouterLink,
              {
                to: '/',
                class: 'brand',
                style: {
                  color: 'var(--el-text-color-primary)',
                  fontSize: '18px',
                  fontWeight: 600,
                  textDecoration: 'none',
                },
              },
              () => props.title,
            ),
            h(
              'div',
              {
                class: 'shell-actions',
                style: { display: 'flex', alignItems: 'center', gap: '16px' },
              },
              slots.actions?.(),
            ),
          ],
        ),
        h(ElContainer, null, () => [
          h(ElAside, { width: '220px', class: 'shell-aside' }, () =>
            h(
              ElMenu,
              {
                defaultActive: active.value,
                class: 'shell-menu',
                // 这些变量定义在 .el-menu 上，只能在同一元素覆盖
                style: {
                  height: '100%',
                  paddingTop: 'var(--forge-space-1)',
                  '--el-menu-item-height': '40px',
                  '--el-menu-base-level-padding': 'var(--forge-space-3)',
                  '--el-menu-hover-bg-color': 'var(--el-fill-color-light)',
                },
              },
              () => props.menu.map(renderItem),
            ),
          ),
          h(
            ElMain,
            {
              class: 'shell-main',
              style: {
                display: 'flex',
                flexDirection: 'column',
                minWidth: 0,
                padding: 'var(--forge-space-4)',
                backgroundColor: 'var(--forge-color-page)',
              },
            },
            () =>
              // 页面默认落在同一块内容面上，未单独设计的页面也有统一边界
              h(
                'div',
                {
                  class: 'shell-surface',
                  style: {
                    flex: 1,
                    minWidth: 0,
                    padding: 'var(--forge-space-4)',
                    backgroundColor: 'var(--el-bg-color)',
                    borderRadius: 'var(--forge-radius-lg)',
                    boxShadow: 'var(--forge-shadow-card)',
                  },
                },
                slots.default?.(),
              ),
          ),
        ]),
      ])
    }
  },
})
