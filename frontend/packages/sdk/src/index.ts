/** 技术验证阶段的 SDK 模块身份，用引用相等性发现重复加载。 */
export const sdkRuntime = Object.freeze({ version: '0.1.0' })

export { createTenantClient, tenantClientKey } from './auth'
export type { TenantClient, TenantSession } from './auth'
