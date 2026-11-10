import { defineComponent, h, ref, watch, type PropType } from 'vue'
import { ElButton, ElDialog } from 'element-plus'
import { IoTaskList } from './IoTaskList'
import { useIoTasks, type IoTask, type IoTaskApi, type IoUploadTicket } from './io-tasks'
import { useUiI18n } from './i18n'

/** 下载模板、直传校验、确认写入、轮询进度与下载错误报告的通用对话框。 */
export const ImportDialog = defineComponent({
  name: 'ImportDialog',
  props: {
    /** 对话框是否打开。 */
    modelValue: { type: Boolean, required: true },
    /** 下载当前租户模板的业务入口。 */
    downloadTemplate: { type: Function as PropType<() => Promise<void>>, required: true },
    /** 申请预签名直传票据。 */
    prepareUpload: {
      type: Function as PropType<(file: File) => Promise<IoUploadTicket>>,
      required: true,
    },
    /** 业务导入接口，负责确认上传。 */
    submit: { type: Function as PropType<(objectId: string) => Promise<IoTask>>, required: true },
    /** 业务方确认入口，使用与提交相同的模型与校验后写入。 */
    confirm: { type: Function as PropType<(taskId: string) => Promise<IoTask>>, required: true },
    /** 当前会话的 Membership。 */
    membershipId: { type: String, required: true },
    /** 当前业务服务的本人任务接口。 */
    tasks: { type: Object as PropType<IoTaskApi>, required: true },
  },
  emits: { 'update:modelValue': (_value: boolean) => true, completed: (_task: IoTask) => true },
  setup(props, { emit }) {
    const { t } = useUiI18n()
    const state = useIoTasks(
      () => props.tasks,
      'IMPORT',
      () => t('io.failed'),
      (task) => emit('completed', task),
    )
    watch(
      () => props.modelValue,
      (open) => {
        if (open) void state.refresh()
      },
    )
    async function confirm(id: string) {
      if (state.busy.value) return
      state.busy.value = true
      state.error.value = ''
      try {
        state.track(await props.confirm(id))
      } catch (cause) {
        state.showError(cause)
        state.busy.value = false
        await state.refresh()
      }
    }
    const file = ref<File>()
    const uploading = ref(false)
    async function template() {
      try {
        await props.downloadTemplate()
      } catch (cause) {
        state.showError(cause)
      }
    }
    async function submit() {
      const selected = file.value
      if (!selected || state.busy.value) return
      if (!selected.name.toLowerCase().endsWith('.xlsx')) {
        state.error.value = t('io.xlsxOnly')
        return
      }
      state.busy.value = true
      state.error.value = ''
      uploading.value = true
      try {
        const ticket = await props.prepareUpload(selected)
        const response = await fetch(ticket.url, {
          method: 'PUT',
          headers: ticket.headers,
          body: selected,
        })
        if (!response.ok) throw new Error('Upload failed')
        uploading.value = false
        state.track(await props.submit(ticket.objectId))
      } catch (cause) {
        state.showError(cause)
        state.busy.value = false
      } finally {
        uploading.value = false
      }
    }
    return () =>
      h(
        ElDialog,
        {
          modelValue: props.modelValue,
          title: t('io.import'),
          width: 'min(640px, 94vw)',
          'onUpdate:modelValue': (value: boolean) => emit('update:modelValue', value),
        },
        () => [
          h(ElButton, { onClick: template }, () => t('io.template')),
          h('input', {
            type: 'file',
            accept: '.xlsx',
            'aria-label': t('io.file'),
            disabled: state.busy.value,
            onChange: (event: Event) => {
              file.value = (event.target as HTMLInputElement).files?.[0]
            },
          }),
          h(
            ElButton,
            {
              loading: state.busy.value,
              disabled: !file.value,
              'data-testid': 'import-submit',
              onClick: submit,
            },
            () => t('io.import'),
          ),
          uploading.value
            ? h('p', { role: 'status' }, t('io.uploading'))
            : state.active.value && state.active.value.status !== 'AWAITING_CONFIRM'
              ? h('p', { role: 'status' }, t('io.' + state.active.value.status))
              : null,
          state.error.value ? h('p', { role: 'alert' }, state.error.value) : null,
          h(IoTaskList, {
            tasks: state.recent.value,
            page: state.page.value,
            loading: state.loading.value,
            onPageChange: state.refresh,
            membershipId: props.membershipId,
            allowConfirm: true,
            busy: state.busy.value,
            onConfirm: confirm,
            onDownload: state.download,
          }),
        ],
      )
  },
})
