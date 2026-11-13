import { defineComponent, h, ref, watch, type PropType } from 'vue'
import { ElButton, ElDialog, ElPagination, ElTable, ElTableColumn, ElTag } from 'element-plus'
import { ImportRollbackDialog } from './ImportRollbackDialog'
import { useIoTasks, type IoTask, type IoTaskApi, type ImportRollbackApi } from './io-tasks'
import { useUiI18n } from './i18n'
import { overlaySize } from './theme'
import { formatDateTime } from './format'

/** 本人导入历史；执行入口由业务方独立权限控制，快照不可用原因保持可见。 */
export const ImportHistoryDialog = defineComponent({
  name: 'ImportHistoryDialog',
  props: {
    /** 对话框开关。 */
    modelValue: { type: Boolean, required: true },
    /** 当前服务的本人导入任务分页入口。 */
    tasks: { type: Object as PropType<IoTaskApi>, required: true },
    /** 当前会话成员，用于确认和回滚入口归属校验。 */
    membershipId: { type: String, required: true },
    /** 可选的导入确认入口，调用方按持久化模型选择地址。 */
    confirm: Function as PropType<(taskId: string) => Promise<IoTask>>,
    /** 具备独立回滚权限时才提供两阶段接口。 */
    rollback: Object as PropType<ImportRollbackApi>,
    /** 调用方按业务模型提供用户文案，未知模型不显示内部类名。 */
    modelLabel: { type: Function as PropType<(model?: string) => string>, required: true },
  },
  emits: { 'update:modelValue': (_value: boolean) => true, completed: () => true },
  setup(props, { emit }) {
    const { t } = useUiI18n()
    const selected = ref('')
    const state = useIoTasks(
      () => props.tasks,
      'IMPORT',
      () => t('io.failed'),
      () => emit('completed'),
    )
    watch(
      () => props.modelValue,
      (open) => {
        if (open) void state.refresh()
      },
    )
    const rollbackState = (task: IoTask) => {
      if (task.rollbackStatus && task.rollbackStatus !== 'NONE')
        return t(`rollback.${task.rollbackStatus}`)
      if (task.status !== 'SUCCEEDED')
        return `${t('rollback.unavailable')}：${t('rollback.reason.TASK_NOT_SUCCEEDED')}`
      if (!task.snapshotCount)
        return `${t('rollback.unavailable')}：${t('rollback.reason.NO_SNAPSHOTS')}`
      if (task.snapshotExpiresAt && Date.parse(task.snapshotExpiresAt) <= Date.now())
        return `${t('rollback.unavailable')}：${t('rollback.reason.SNAPSHOT_EXPIRED')}`
      return t(
        task.snapshotAvailable ? 'rollback.available' : 'rollback.reason.SNAPSHOT_UNAVAILABLE',
      )
    }
    async function confirm(id: string) {
      if (state.busy.value || !props.confirm) return
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
    return () => [
      h(
        ElDialog,
        {
          modelValue: props.modelValue,
          title: t('rollback.history'),
          width: overlaySize.dialogLg,
          'onUpdate:modelValue': (value: boolean) => emit('update:modelValue', value),
        },
        () => [
          state.error.value ? h('p', { role: 'alert' }, state.error.value) : null,
          h(
            ElTable,
            { data: state.recent.value, emptyText: t('io.empty'), style: { width: '100%' } },
            () => [
              h(
                ElTableColumn,
                { label: t('rollback.task'), minWidth: 160, showOverflowTooltip: true },
                { default: ({ row }: { row: IoTask }) => row.id },
              ),
              h(
                ElTableColumn,
                { label: t('rollback.mode'), minWidth: 100 },
                { default: ({ row }: { row: IoTask }) => props.modelLabel(row.importModel) },
              ),
              h(
                ElTableColumn,
                { label: t('rollback.status'), minWidth: 100 },
                {
                  default: ({ row }: { row: IoTask }) =>
                    h(
                      ElTag,
                      {
                        type:
                          row.status === 'SUCCEEDED'
                            ? 'success'
                            : row.status === 'FAILED'
                              ? 'danger'
                              : 'info',
                      },
                      () => t(`io.${row.status}`),
                    ),
                },
              ),
              h(ElTableColumn, {
                label: t('rollback.rows'),
                minWidth: 65,
                align: 'right',
                prop: 'rowCount',
              }),
              h(
                ElTableColumn,
                { label: t('rollback.rollbackStatus'), minWidth: 200 },
                {
                  default: ({ row }: { row: IoTask }) =>
                    h('div', [
                      h('span', rollbackState(row)),
                      row.rollbackStatus &&
                      row.rollbackStatus !== 'NONE' &&
                      row.rollbackStatus !== 'SUCCEEDED' &&
                      row.snapshotExpiresAt &&
                      Date.parse(row.snapshotExpiresAt) <= Date.now()
                        ? h(
                            'p',
                            { style: { margin: 'var(--forge-space-2) 0 0' } },
                            `${t('rollback.unavailable')}：${t('rollback.reason.SNAPSHOT_EXPIRED')}`,
                          )
                        : null,
                      row.rollbackAttempts
                        ? h(
                            'p',
                            { style: { margin: 'var(--forge-space-2) 0 0' } },
                            t('rollback.counts', {
                              restoredCount: row.restoredCount ?? 0,
                              skippedCount: row.skippedCount ?? 0,
                              failedCount: row.failedCount ?? 0,
                            }),
                          )
                        : null,
                      !props.rollback && row.snapshotAvailable
                        ? h(
                            'p',
                            { style: { margin: 'var(--forge-space-2) 0 0' } },
                            t('rollback.noPermission'),
                          )
                        : null,
                    ]),
                },
              ),
              h(
                ElTableColumn,
                { label: t('rollback.deadline'), minWidth: 175 },
                { default: ({ row }: { row: IoTask }) => formatDateTime(row.snapshotExpiresAt) },
              ),
              h(
                ElTableColumn,
                { label: t('rollback.actions'), width: 110, fixed: 'right' },
                {
                  default: ({ row }: { row: IoTask }) => [
                    props.confirm &&
                    row.status === 'AWAITING_CONFIRM' &&
                    row.membershipId === props.membershipId &&
                    !!row.heartbeatAt &&
                    Date.parse(row.heartbeatAt) + 24 * 60 * 60 * 1000 > Date.now()
                      ? h(
                          ElButton,
                          {
                            link: true,
                            type: 'primary',
                            disabled: state.busy.value,
                            onClick: () => confirm(row.id),
                          },
                          () => t('io.confirm'),
                        )
                      : null,
                    props.rollback &&
                    row.status === 'SUCCEEDED' &&
                    row.membershipId === props.membershipId &&
                    row.snapshotAvailable
                      ? h(
                          ElButton,
                          {
                            link: true,
                            type: 'primary',
                            onClick: () => {
                              selected.value = row.id
                            },
                          },
                          () => t('rollback.title'),
                        )
                      : null,
                  ],
                },
              ),
            ],
          ),
          state.page.value && state.page.value.totalPages > 1
            ? h(
                'footer',
                {
                  style: {
                    display: 'flex',
                    justifyContent: 'flex-end',
                    marginTop: 'var(--forge-space-4)',
                  },
                },
                [
                  h(ElPagination, {
                    currentPage: state.page.value.number,
                    pageSize: state.page.value.size,
                    total: state.page.value.totalElements,
                    layout: 'prev, pager, next',
                    disabled: state.loading.value,
                    'onUpdate:currentPage': state.refresh,
                  }),
                ],
              )
            : null,
        ],
      ),
      props.rollback
        ? h(ImportRollbackDialog, {
            modelValue: !!selected.value,
            taskId: selected.value,
            api: props.rollback,
            'onUpdate:modelValue': (open: boolean) => {
              if (!open) selected.value = ''
            },
            onCompleted: () => {
              void state.refresh()
              emit('completed')
            },
          })
        : null,
    ]
  },
})
