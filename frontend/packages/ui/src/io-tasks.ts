import { ApiError } from '@crane199709/sdk'
import { onMounted, onUnmounted, ref } from 'vue'

/** 当前 Membership 在所属服务内的任务。 */
export interface IoTask {
  /** 任务 ID。 */
  id: string
  /** 任务方向。 */
  type: 'IMPORT' | 'EXPORT'
  /** 服务端状态。 */
  status: 'WAITING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  /** 已处理行数。 */
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
  /** 最近任务，第一页即可。 */
  list: () => Promise<IoTask[]>
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
export function useIoTasks(
  api: () => IoTaskApi,
  type: IoTask['type'],
  fallback: () => string,
  done?: (task: IoTask) => void,
) {
  const recent = ref<IoTask[]>([])
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
  async function refresh() {
    if (stopped) return
    const request = ++generation
    if (timer) clearTimeout(timer)
    try {
      const list = await api().list()
      if (stopped || request !== generation) return
      recent.value = list.filter((task) => task.type === type)
      if (tracked) {
        const task = await api().get(tracked)
        if (stopped || request !== generation) return
        active.value = task
        if (task.status === 'SUCCEEDED' || task.status === 'FAILED') {
          tracked = undefined
          busy.value = false
          recent.value = [task, ...recent.value.filter((item) => item.id !== task.id)]
          done?.(task)
        }
      }
    } catch (cause) {
      if (!stopped && request === generation) showError(cause)
    }
    if (
      !stopped &&
      request === generation &&
      (tracked ||
        recent.value.some((task) => task.status === 'WAITING' || task.status === 'RUNNING'))
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
  return { recent, active, busy, error, track, refresh, download, showError }
}
