import { defineComponent, h, type PropType } from 'vue'
import { ElButton, ElPopover } from 'element-plus'
import { IoTaskList } from './IoTaskList'
import { useIoTasks, type IoTask, type IoTaskApi } from './io-tasks'
import { useUiI18n } from './i18n'

/** 提交沿用当前列表条件的导出，并展示所属服务内最近任务。 */
export const ExportButton = defineComponent({
  name: 'ExportButton',
  props: {
    /** 业务方挂权限码的导出提交接口。 */
    submit: { type: Function as PropType<() => Promise<IoTask>>, required: true },
    /** 当前业务服务的本人任务接口。 */
    tasks: { type: Object as PropType<IoTaskApi>, required: true },
  },
  setup(props) {
    const { t } = useUiI18n()
    const state = useIoTasks(
      () => props.tasks,
      'EXPORT',
      () => t('io.failed'),
      (task) => {
        if (task.status === 'SUCCEEDED') void state.download(task.id)
      },
    )
    async function submit() {
      state.busy.value = true
      state.error.value = ''
      try {
        state.track(await props.submit())
      } catch (cause) {
        state.showError(cause)
        state.busy.value = false
      }
    }
    return () =>
      h(
        'span',
        {
          style: {
            display: 'inline-flex',
            verticalAlign: 'middle',
            alignItems: 'center',
            gap: 'var(--forge-space-3)',
            marginLeft: 'var(--forge-space-3)',
          },
        },
        [
          h(
            ElButton,
            { loading: state.busy.value, 'data-testid': 'export-submit', onClick: submit },
            () => t('io.export'),
          ),
          // 任务记录放进弹层：常驻会把所在工具栏撑成多行。
          h(
            ElPopover,
            { trigger: 'click', placement: 'bottom-end', width: 360 },
            {
              reference: () =>
                h(ElButton, { link: true, 'data-testid': 'export-history' }, () => t('io.history')),
              default: () =>
                h(IoTaskList, {
                  tasks: state.recent.value,
                  page: state.page.value,
                  loading: state.loading.value,
                  onPageChange: state.refresh,
                  onDownload: state.download,
                }),
            },
          ),
          state.active.value
            ? h('span', { role: 'status' }, t('io.' + state.active.value.status))
            : null,
          state.error.value ? h('span', { role: 'alert' }, state.error.value) : null,
        ],
      )
  },
})
