import { defineComponent, h, type PropType } from 'vue'
import { ElButton } from 'element-plus'
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
      h('section', [
        h(
          ElButton,
          { loading: state.busy.value, 'data-testid': 'export-submit', onClick: submit },
          () => t('io.export'),
        ),
        state.active.value
          ? h('p', { role: 'status' }, t('io.' + state.active.value.status))
          : null,
        state.error.value ? h('p', { role: 'alert' }, state.error.value) : null,
        h(IoTaskList, { tasks: state.recent.value, onDownload: state.download }),
      ])
  },
})
