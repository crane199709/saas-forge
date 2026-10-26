/** demo-service /context 的响应模型，由调用方维护，不属于通用 SDK。 */
export interface TenantContextResponse {
  identityId: string
  tenantId: string
  membershipId: string
  version: number
}
