import { defineComponent, h, ref, watch, type PropType } from 'vue'
import { ApiError } from '@crane199709/sdk'
import {
  ElAlert,
  ElButton,
  ElCheckbox,
  ElDialog,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { formatDateTime } from './format'
import { overlaySize } from './theme'
import { useUiI18n } from './i18n'
import type { ImportRollbackApi, ImportRollbackPreview, ImportRollbackResult } from './io-tasks'

/** 先读取冲突报告，管理员明确确认后仅恢复被覆盖的字段。 */
export const ImportRollbackDialog = defineComponent({
  name: 'ImportRollbackDialog',
  props: {
    /** 对话框开关。 */
    modelValue: { type: Boolean, required: true },
    /** 本次预览与执行的本人导入任务。 */
    taskId: { type: String, required: true },
    /** 已绑定业务服务与独立回滚权限的HTTP边界。 */
    api: { type: Object as PropType<ImportRollbackApi>, required: true },
  },
  emits: {
    'update:modelValue': (_value: boolean) => true,
    completed: (_result: ImportRollbackResult) => true,
  },
  setup(props, { emit }) {
    const { t, te } = useUiI18n()
    const preview = ref<ImportRollbackPreview>()
    const result = ref<ImportRollbackResult>()
    const skip = ref(false)
    const busy = ref(false)
    const error = ref('')
    let generation = 0
    const reason = (code?: string | null) =>
      t(
        te(`rollback.reason.${code}`)
          ? `rollback.reason.${code}`
          : 'rollback.reason.SNAPSHOT_UNAVAILABLE',
      )
    async function load() {
      const request = ++generation
      busy.value = true
      error.value = ''
      skip.value = false
      preview.value = undefined
      try {
        const report = await props.api.preview(props.taskId)
        if (request === generation) preview.value = report
      } catch (cause) {
        if (request === generation)
          error.value = cause instanceof ApiError && cause.userTip ? cause.userTip : t('io.failed')
      } finally {
        if (request === generation) busy.value = false
      }
    }
    watch(
      () => [props.modelValue, props.taskId] as const,
      ([open]) => {
        generation++
        result.value = undefined
        if (open) void load()
      },
      { immediate: true },
    )
    async function execute() {
      if (busy.value || !preview.value?.available || (preview.value.blocked && !skip.value)) return
      busy.value = true
      error.value = ''
      try {
        result.value = await props.api.execute(props.taskId, skip.value)
        emit('completed', result.value)
        if (result.value.failedCount > 0 || result.value.skippedCount > 0) await load()
      } catch (cause) {
        // 执行会重新检查；409 后重取报告，避免沿用过时的预览继续恢复。
        await load()
        error.value =
          cause instanceof ApiError &&
          ['io.rollbackConflicts', 'io.rollbackUnavailable'].includes(cause.code)
            ? t('rollback.changed')
            : cause instanceof ApiError && cause.userTip
              ? cause.userTip
              : t('io.failed')
      } finally {
        busy.value = false
      }
    }
    return () =>
      h(
        ElDialog,
        {
          modelValue: props.modelValue,
          title: t('rollback.title'),
          width: overlaySize.dialogLg,
          closeOnClickModal: !busy.value,
          closeOnPressEscape: !busy.value,
          showClose: !busy.value,
          'onUpdate:modelValue': (value: boolean) => emit('update:modelValue', value),
        },
        {
          default: () => [
            h(ElAlert, { title: t('rollback.impact'), type: 'warning', closable: false }),
            error.value ? h('p', { role: 'alert' }, error.value) : null,
            busy.value && !preview.value ? h('p', { role: 'status' }, t('rollback.loading')) : null,
            result.value
              ? h('p', { role: 'status', 'data-testid': 'rollback-result' }, [
                  h(
                    ElTag,
                    { type: result.value.status === 'SUCCEEDED' ? 'success' : 'warning' },
                    () => t(`rollback.${result.value!.status}`),
                  ),
                  ' ' + t('rollback.counts', { ...result.value }),
                ])
              : null,
            preview.value && result.value?.status !== 'SUCCEEDED'
              ? h('div', [
                  h(
                    'p',
                    { style: { overflowWrap: 'anywhere' } },
                    `${t('rollback.task')}: ${props.taskId}`,
                  ),
                  h(
                    'p',
                    `${t('rollback.deadline')}: ${formatDateTime(preview.value.snapshotExpiresAt)}`,
                  ),
                  !preview.value.available
                    ? h(ElAlert, {
                        type: 'info',
                        closable: false,
                        title: `${t('rollback.unavailable')}：${reason(preview.value.unavailableReason)}`,
                      })
                    : [
                        h(
                          'p',
                          { 'data-testid': 'rollback-summary' },
                          t('rollback.preview', { ...preview.value }),
                        ),
                        preview.value.conflictCount > 0
                          ? h(
                              ElTable,
                              {
                                data: preview.value.conflicts,
                                maxHeight: 280,
                                style: { width: '100%' },
                              },
                              () => [
                                h(ElTableColumn, {
                                  prop: 'recordId',
                                  label: t('rollback.record'),
                                  minWidth: 240,
                                  showOverflowTooltip: true,
                                }),
                                h(
                                  ElTableColumn,
                                  { label: t('rollback.conflictReason'), minWidth: 260 },
                                  {
                                    default: ({ row }: { row: { reason: string } }) =>
                                      reason(row.reason),
                                  },
                                ),
                              ],
                            )
                          : null,
                        preview.value.blocked
                          ? h(
                              ElCheckbox,
                              {
                                modelValue: skip.value,
                                disabled: busy.value,
                                style: {
                                  marginTop: 'var(--forge-space-4)',
                                  whiteSpace: 'normal',
                                  height: 'auto',
                                },
                                'onUpdate:modelValue': (value: string | number | boolean) => {
                                  skip.value = value === true
                                },
                              },
                              () => t('rollback.skip'),
                            )
                          : null,
                      ],
                ])
              : null,
          ],
          footer: () => [
            h(
              ElButton,
              { disabled: busy.value, onClick: () => emit('update:modelValue', false) },
              () => t('rollback.close'),
            ),
            !preview.value && !busy.value
              ? h(ElButton, { onClick: load }, () => t('rollback.reload'))
              : null,
            preview.value?.available && result.value?.status !== 'SUCCEEDED'
              ? h(
                  ElButton,
                  {
                    type: 'primary',
                    loading: busy.value,
                    disabled: busy.value || (preview.value.blocked && !skip.value),
                    onClick: execute,
                  },
                  () => t(result.value ? 'rollback.retry' : 'rollback.confirm'),
                )
              : null,
          ],
        },
      )
  },
})
