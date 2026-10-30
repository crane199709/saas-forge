import { ElResult } from 'element-plus'
import { defineComponent, h, type PropType } from 'vue'
import { useUiI18n } from './i18n'

/** 结果页的种类。 */
export type ResultStatus = '403' | '404' | 'no-tenant' | 'no-permission'

const icons = {
  '403': 'warning',
  '404': 'info',
  'no-tenant': 'info',
  'no-permission': 'warning',
} as const

/** 403、404、无租户、无权限等结果页；默认插槽放操作按钮，如「返回首页」。 */
export const ResultPage = defineComponent({
  name: 'ResultPage',
  props: {
    /** 结果种类，决定图标与默认文案，结果元素带 `data-testid="status-<status>"`。 */
    status: { type: String as PropType<ResultStatus>, required: true },
    /** 覆盖默认标题。 */
    title: String,
    /** 覆盖默认说明。 */
    subTitle: String,
  },
  setup(props, { slots }) {
    const { t } = useUiI18n()
    return () =>
      h(
        'section',
        { class: 'result-page', style: { padding: '80px 16px' } },
        h(
          ElResult,
          {
            icon: icons[props.status],
            title: props.title ?? t(`result.${props.status}.title`),
            subTitle: props.subTitle ?? t(`result.${props.status}.subTitle`),
            'data-testid': `status-${props.status}`,
          },
          slots.default ? { extra: slots.default } : undefined,
        ),
      )
  },
})
