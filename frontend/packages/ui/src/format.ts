/**
 * 把接口返回的 ISO 8601 时间格式化为浏览器时区的本地时间；语言固定为第一期唯一支持的 `zh-CN`。
 *
 * @param value ISO 8601 时间；缺失时返回 `—`
 */
export function formatDateTime(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString('zh-CN') : '—'
}
