package io.saasforge.sdk.audit;

/** Audit Event 所属的 Realm：Tenant Admin 只看本租户的 Tenant Realm 事件，超级管理员查看 Platform Realm 事件，两者分开存放与查询。 */
public enum AuditRealm {
    /** 成员在租户中的登录与操作，以及租户内的权限变更。 */
    TENANT,
    /** Operator 的登录与操作，以及平台角色、运营账号的变更。 */
    PLATFORM
}
