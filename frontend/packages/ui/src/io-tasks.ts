import { ApiError, type Page, type PageMeta } from '@crane199709/sdk'
import { onMounted, onUnmounted, ref } from 'vue'

/** 当前 Membership 在所属服务内的任务。 */
export interface IoTask {
  /** 任务 ID。 */
  id: string
  /** 任务方向。 */
  type: 'IMPORT' | 'EXPORT'
  /** 服务端状态。 */
  status: 'WAITING' | 'RUNNING' | 'AWAITING_CONFIRM' | 'SUCCEEDED' | 'FAILED'
  /** 服务端持久化的导入模型；确认时须按它选择业务入口。 */
  importModel?: string
  /** 覆盖前快照数量及实际保留截止时间。 */
  snapshotCount?: number
  snapshotExpiresAt?: string | null
  snapshotAvailable?: boolean
  /** 独立回滚结果，不改变原导入状态。 */
  rollbackStatus?: 'NONE' | 'SUCCEEDED' | 'PARTIAL' | 'BLOCKED'
  rollbackAttempts?: number
  restoredCount?: number
  skippedCount?: number
  failedCount?: number
  createdAt?: string
  /** 提交任务的 Membership。 */
  membershipId?: string
  /** 最后心跳；待确认时记录进入该状态的时间。 */
  heartbeatAt?: string
  /** 服务端失败原因标识。 */
  errorCode?: string
  /** 已校验或处理行数。 */
  rowCount: number
  /** 可直接展示的失败原因。 */
  failureReason?: string
  /** 结果是否已过期。 */
  resultAvailable?: boolean
  /** 结束时间。 */
  finishedAt?: string
}
/** 只绑定一个业务服务的本人任务接口。 */
export interface IoTaskApi {
  /** 按页获取任务，页码从 1 开始。 */
  list: (page: number) => Promise<Page<IoTask>>
  /** 获取本人的单个任务。 */
  get: (id: string) => Promise<IoTask>
  /** 现取下载票据；服务端校验归属与有效期。 */
  download: (id: string) => Promise<{ url: string }>
}
/** 直传票据，PUT 请求只带票据头，不带租户认证。 */
export interface IoUploadTicket {
  /** 上传完成后提交的对象 ID。 */
  objectId: string
  /** 预签名 URL。 */
  url: string
  /** 需要带入 PUT 的签名头。 */
  headers: Record<string, string>
}
/** 轮询所属服务任务；待确认释放执行忙碌状态，只有最终成功或失败才通知完成。 */
export function useIoTasks(
  api: () => IoTaskApi,
  type: IoTask['type'],
  fallback: () => string,
  done?: (task: IoTask) => void,
) {
  const recent = ref<IoTask[]>([])
  const page = ref<PageMeta>()
  const loading = ref(false)
  const error = ref('')
  const active = ref<IoTask | null>(null)
  const busy = ref(false)
  let stopped = false
  let timer: ReturnType<typeof setTimeout> | undefined
  let tracked: string | undefined
  let generation = 0
  function showError(cause: unknown) {
    error.value = cause instanceof ApiError && cause.userTip ? cause.userTip : fallback()
  }
  async function download(id: string) {
    try {
      const ticket = await api().download(id)
      if (!stopped) window.open(ticket.url, '_blank', 'noopener')
    } catch (cause) {
      if (!stopped) showError(cause)
    }
  }
  async function refresh(number = page.value?.number ?? 1) {
    if (stopped) return
    const request = ++generation
    loading.value = true
    if (timer) clearTimeout(timer)
    try {
      const list = await api().list(number)
      if (stopped || request !== generation) return
      page.value = list.page
      recent.value = list.items.filter((task) => task.type === type)
      if (tracked) {
        const task = await api().get(tracked)
        if (stopped || request !== generation) return
        active.value = task
        if (['AWAITING_CONFIRM', 'SUCCEEDED', 'FAILED'].includes(task.status)) {
          tracked = undefined
          busy.value = false
          recent.value = [task, ...recent.value.filter((item) => item.id !== task.id)]
          if (task.status !== 'AWAITING_CONFIRM') done?.(task)
        }
      }
    } catch (cause) {
      if (!stopped && request === generation) showError(cause)
    } finally {
      if (!stopped && request === generation) loading.value = false
    }
    if (
      !stopped &&
      request === generation &&
      (tracked ||
        recent.value.some((task) =>
          ['WAITING', 'RUNNING', 'AWAITING_CONFIRM'].includes(task.status),
        ))
    )
      timer = setTimeout(() => void refresh(), 1000)
  }
  function track(task: IoTask) {
    active.value = task
    tracked = task.id
    busy.value = true
    void refresh()
  }
  onMounted(() => void refresh())
  onUnmounted(() => {
    stopped = true
    if (timer) clearTimeout(timer)
  })
  return { recent, page, loading, active, busy, error, track, refresh, download, showError }
}

/** 不含业务字段原文的冲突。 */
export interface ImportRollbackConflict {
  recordId: string
  reason: string
}
/** 服务端最新冲突报告；available 与 blocked 表达不同状态。 */
export interface ImportRollbackPreview {
  taskId: string
  available: boolean
  unavailableReason?: string | null
  blocked: boolean
  totalCount: number
  rollbackableCount: number
  conflictCount: number
  snapshotExpiresAt?: string | null
  conflicts: ImportRollbackConflict[]
}
/** 回滚累计结果；重试不会重复恢复已完成的记录。 */
export interface ImportRollbackResult {
  taskId: string
  status: 'SUCCEEDED' | 'PARTIAL' | 'BLOCKED' | 'UNAVAILABLE'
  restoredCount: number
  skippedCount: number
  failedCount: number
  unavailableReason?: string | null
  conflicts: ImportRollbackConflict[]
}
/** 由业务服务绑定独立回滚权限的两阶段入口。 */
export interface ImportRollbackApi {
  preview: (taskId: string) => Promise<ImportRollbackPreview>
  execute: (taskId: string, skipConflicts: boolean) => Promise<ImportRollbackResult>
}
