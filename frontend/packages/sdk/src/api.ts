/** 分页元数据，位于信封 `meta.page`；`number` 从 1 开始。 */
export interface PageMeta {
  number: number
  size: number
  totalElements: number
  totalPages: number
}

/** 校验失败时的逐字段明细。 */
export interface FieldError {
  field: string
  code: string
  userTip: string
}

/** 服务端统一响应信封；值为 null 的字段服务端不输出，因此都可能缺省。 */
export interface ApiResponse<T> {
  /** 成功为 `"ok"`，失败为 `<领域>.<原因>` 错误码。 */
  code: string
  httpStatus: number
  data?: T
  /** 面向开发者的英文说明，不在界面展示。 */
  errorMessage?: string
  /** 面向最终用户、已按语言翻译的提示，可直接展示。 */
  userTip?: string
  traceId?: string
  meta?: { page?: PageMeta }
  errors?: FieldError[]
}

/** 分页接口的结果。 */
export interface Page<T> {
  items: T[]
  page: PageMeta
}

/**
 * 接口失败。`message` 与 `code` 相同，便于沿用按错误码字符串判断的代码；
 * 响应不是信封（如代理返回的 HTML）时 `code` 为 `system.error` 且没有 `userTip`。
 */
export class ApiError extends Error {
  readonly code: string
  readonly httpStatus: number
  readonly errorMessage?: string
  readonly userTip?: string
  readonly traceId?: string
  readonly errors?: FieldError[]
  /** 响应头 `Retry-After` 给出的等待秒数，如 `auth.locked` 的剩余锁定时间；没有该头时缺省。 */
  readonly retryAfter?: number

  constructor(body: Omit<ApiResponse<unknown>, 'data' | 'meta'>, retryAfter?: number) {
    super(body.code)
    this.name = 'ApiError'
    this.code = body.code
    this.httpStatus = body.httpStatus
    this.errorMessage = body.errorMessage
    this.userTip = body.userTip
    this.traceId = body.traceId
    this.errors = body.errors
    this.retryAfter = retryAfter
  }
}

/** 解析信封：成功返回整个信封，失败或无法解析时抛出 {@link ApiError}。 */
export async function readEnvelope<T>(response: Response): Promise<ApiResponse<T>> {
  let body: unknown
  try {
    body = await response.json()
  } catch {
    body = undefined
  }
  if (!isEnvelope(body)) {
    throw invalidResponse(response, 'Response is not an API envelope')
  }
  if (body.code !== 'ok') throw new ApiError(body, retryAfter(response))
  return body as ApiResponse<T>
}

/** 分页信封转为条目与分页信息；缺少 `meta.page` 视为响应无效。 */
export async function readPage<T>(response: Response): Promise<Page<T>> {
  const body = await readEnvelope<T[]>(response)
  const page = body.meta?.page
  if (!page || !Array.isArray(body.data)) {
    throw invalidResponse(response, 'Response has no page metadata', body.traceId)
  }
  return { items: body.data, page }
}

// 只接受秒数形式；HTTP 日期形式的 Retry-After 本项目不使用。
function retryAfter(response: Response): number | undefined {
  const value = response.headers.get('Retry-After')
  return value !== null && /^\d+$/.test(value) ? Number(value) : undefined
}

function invalidResponse(response: Response, errorMessage: string, traceId?: string) {
  // 网关回写的响应头是非信封响应唯一可用的排障线索。
  return new ApiError({
    code: 'system.error',
    httpStatus: response.status,
    errorMessage,
    traceId: traceId ?? response.headers.get('X-Trace-Id') ?? undefined,
  })
}

function isEnvelope(body: unknown): body is ApiResponse<unknown> {
  return (
    typeof body === 'object' &&
    body !== null &&
    typeof (body as ApiResponse<unknown>).code === 'string' &&
    typeof (body as ApiResponse<unknown>).httpStatus === 'number'
  )
}
