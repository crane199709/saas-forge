import { defineComponent, h } from 'vue'

/** 列表工具栏：默认插槽放筛选或视图切换，`actions` 插槽靠右放主操作，窄屏时换行。 */
export const PageToolbar = defineComponent({
  name: 'PageToolbar',
  setup(_, { slots }) {
    return () =>
      h(
        'div',
        {
          class: 'page-toolbar',
          style: {
            display: 'flex',
            flexWrap: 'wrap',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 'var(--forge-space-2)',
            marginBottom: 'var(--forge-space-3)',
          },
        },
        [
          h(
            'div',
            {
              class: 'page-toolbar-filters',
              style: {
                display: 'flex',
                flexWrap: 'wrap',
                alignItems: 'center',
                gap: 'var(--forge-space-2)',
              },
            },
            slots.default?.(),
          ),
          slots.actions
            ? h(
                'div',
                {
                  class: 'page-toolbar-actions',
                  style: {
                    display: 'flex',
                    flexWrap: 'wrap',
                    alignItems: 'center',
                    gap: 'var(--forge-space-2)',
                  },
                },
                slots.actions(),
              )
            : null,
        ],
      )
  },
})
