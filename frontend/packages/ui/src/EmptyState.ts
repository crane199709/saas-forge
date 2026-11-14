import { ElIcon } from 'element-plus'
import { defineComponent, h } from 'vue'
import { resolveMenuIcon } from './icons'

/** 空状态属性：标题必填，图标与描述可选，主操作放默认插槽。 */
export interface EmptyStateProps {
  /** 用一句话说明这里为什么是空的，如「暂无待办」。 */
  title: string
  /** 补充说明，可选：解释原因或下一步。 */
  description?: string
  /** 图标名，可选：取 `@forge/ui` 图标白名单，未命中回退默认图标。 */
  icon?: string
}

/**
 * 统一的空状态：一个图标、一句标题、可选的补充说明，默认插槽放主操作。
 * 场景差异由入参表达，不为不同页面另建变体组件。
 */
export const EmptyState = defineComponent({
  name: 'EmptyState',
  props: {
    title: { type: String, required: true },
    description: String,
    icon: String,
  },
  setup(props, { slots }) {
    return () =>
      h(
        'section',
        {
          class: 'empty-state',
          style: {
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: 'var(--forge-space-2)',
            padding: 'var(--forge-space-5) var(--forge-space-4)',
            textAlign: 'center',
          },
        },
        [
          props.icon
            ? h(
                'div',
                {
                  class: 'empty-state-icon',
                  'aria-hidden': 'true',
                  style: {
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    width: 'var(--forge-space-7)',
                    height: 'var(--forge-space-7)',
                    borderRadius: '50%',
                    color: 'var(--el-text-color-secondary)',
                    background: 'var(--el-fill-color-light)',
                    fontSize: 'var(--forge-font-size-lg)',
                  },
                },
                h(ElIcon, () => h(resolveMenuIcon(props.icon))),
              )
            : null,
          h(
            'p',
            {
              class: 'empty-state-title',
              style: {
                margin: 0,
                color: 'var(--el-text-color-primary)',
                fontFamily: 'var(--forge-font-family)',
                fontSize: 'var(--forge-font-size-md)',
                fontWeight: '600',
              },
            },
            props.title,
          ),
          props.description
            ? h(
                'p',
                {
                  class: 'empty-state-description',
                  style: {
                    margin: 0,
                    color: 'var(--el-text-color-secondary)',
                    fontFamily: 'var(--forge-font-family)',
                    fontSize: 'var(--forge-font-size-sm)',
                  },
                },
                props.description,
              )
            : null,
          slots.default
            ? h(
                'div',
                {
                  class: 'empty-state-actions',
                  style: {
                    marginTop: 'var(--forge-space-1)',
                    fontFamily: 'var(--forge-font-family)',
                    fontSize: 'var(--forge-font-size-sm)',
                  },
                },
                slots.default(),
              )
            : null,
        ],
      )
  },
})
