import { ApiError } from '@crane199709/sdk'
import type { ErrorTip } from '@crane199709/ui'

// SDK 在本地判定、没有服务端 userTip 的错误码，例如授权回调 state 不匹配时的 auth.failed。
const localKeys = ['auth.failed']

// 账号安全相关错误码的前端文案（locales 的 errorCode 下），服务端没有给出 userTip 时使用（如 mfa.identityNotFound）。
const codeKeys = [
  'auth.mfaExpired',
  'mfa.notBound',
  'mfa.identityNotFound',
  'mfa.codeInvalid',
  'mfa.recoveryCodeInvalid',
  'mfa.bindingExpired',
  'password.tooShort',
  'password.tooLong',
  'password.tooCommon',
  'password.sameAsIdentifier',
  'password.currentInvalid',
]

type Translate = (key: string, named?: Record<string, unknown>) => string

/**
 * 优先展示服务端按语言翻译好的 userTip；SDK 本地错误与账号安全错误码按 i18n key 翻译，
 * 网络故障、非信封响应等其余情况显示通用提示。
 */
export function errorTip(cause: unknown, t: Translate): ErrorTip {
  const traceId = cause instanceof ApiError ? cause.traceId : undefined
  if (cause instanceof ApiError) {
    // 锁定的剩余时间取自 Retry-After，由前端换算成分钟。
    if (cause.code === 'auth.locked') {
      if (cause.retryAfter !== undefined) {
        const minutes = Math.max(1, Math.ceil(cause.retryAfter / 60))
        return { message: t('errorCode.auth.lockedFor', { minutes }), traceId }
      }
      return { message: cause.userTip ?? t('errorCode.auth.locked'), traceId }
    }
    if (cause.userTip) return { message: cause.userTip, traceId }
    if (codeKeys.includes(cause.code)) return { message: t(`errorCode.${cause.code}`), traceId }
  }
  const key =
    cause instanceof Error && localKeys.includes(cause.message) ? cause.message : 'error.unknown'
  return { message: t(key), traceId }
}
