import { ElAlert, ElButton } from 'element-plus'
import { defineComponent, h, type PropType } from 'vue'
import { useUiI18n } from './i18n'

/** 界面展示的错误：文案加可选的 traceId，供用户报障时复制。文案由各应用按错误码转换。 */
export interface ErrorTip {
  message: string
  traceId?: string
}

/** 错误提示：展示文案，有 traceId 时一并展示并可复制。 */
export const ErrorAlert = defineComponent({
  name: 'ErrorAlert',
  props: {
    /** 要展示的错误。 */
    tip: { type: Object as PropType<ErrorTip>, required: true },
  },
  setup(props) {
    const { t } = useUiI18n()
    function copy() {
      if (props.tip.traceId) void navigator.clipboard?.writeText(props.tip.traceId)
    }
    return () =>
      h(
        ElAlert,
        { title: props.tip.message, type: 'error', closable: false, role: 'alert' },
        props.tip.traceId
          ? () =>
              h('p', { class: 'trace', style: { margin: '4px 0 0', overflowWrap: 'anywhere' } }, [
                `${t('traceId')}：`,
                h('code', props.tip.traceId),
                h(
                  ElButton,
                  {
                    link: true,
                    type: 'primary',
                    size: 'small',
                    'data-testid': 'copy-trace-id',
                    onClick: copy,
                  },
                  () => t('copyTraceId'),
                ),
              ])
          : undefined,
      )
  },
})
