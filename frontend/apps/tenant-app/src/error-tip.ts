import { ApiError } from '@crane199709/sdk'
import type { ErrorTip } from '@crane199709/ui'

export type { ErrorTip }

// SDK 在本地判定、没有服务端 userTip 的错误码，例如授权回调 state 不匹配时的 auth.failed。
const localKeys = ['auth.failed']

/**
 * 优先展示服务端按语言翻译好的 userTip；SDK 本地错误按 i18n key 翻译，
 * 网络故障、非信封响应等其余情况显示通用提示。
 */
export function errorTip(cause: unknown, t: (key: string) => string): ErrorTip {
  const traceId = cause instanceof ApiError ? cause.traceId : undefined
  if (cause instanceof ApiError && cause.userTip) return { message: cause.userTip, traceId }
  const key =
    cause instanceof Error && localKeys.includes(cause.message) ? cause.message : 'error.unknown'
  return { message: t(key), traceId }
}
