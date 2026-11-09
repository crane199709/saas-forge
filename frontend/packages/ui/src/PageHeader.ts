import { defineComponent, h } from 'vue'

/** 页面标题区：左侧标题与可选说明，右侧 `actions` 插槽放页面级操作。 */
export const PageHeader = defineComponent({
  name: 'PageHeader',
  props: {
    /** 页面标题。 */
    title: { type: String, required: true },
    /** 标题下方的一句话说明。 */
    description: String,
  },
  setup(props, { slots }) {
    return () =>
      h(
        'header',
        {
          class: 'page-header',
          style: {
            display: 'flex',
            alignItems: 'flex-start',
            justifyContent: 'space-between',
            gap: 'var(--forge-space-4)',
            marginBottom: 'var(--forge-space-3)',
          },
        },
        [
          h('div', { style: { minWidth: 0 } }, [
            h(
              'h2',
              {
                style: {
                  margin: 0,
                  color: 'var(--el-text-color-primary)',
                  fontSize: 'var(--forge-font-size-lg)',
                  fontWeight: 600,
                  lineHeight: 1.4,
                },
              },
              props.title,
            ),
            props.description
              ? h(
                  'p',
                  {
                    style: {
                      margin: 'var(--forge-space-1) 0 0',
                      color: 'var(--el-text-color-secondary)',
                      fontSize: 'var(--forge-font-size-sm)',
                    },
                  },
                  props.description,
                )
              : null,
          ]),
          slots.actions
            ? h(
                'div',
                {
                  class: 'page-header-actions',
                  style: { display: 'flex', flexShrink: 0, gap: 'var(--forge-space-2)' },
                },
                slots.actions(),
              )
            : null,
        ],
      )
  },
})
