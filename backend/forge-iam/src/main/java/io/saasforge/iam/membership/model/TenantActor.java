package io.saasforge.iam.membership.model;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 发起租户管理操作（组织、角色等）的 Membership，取自已验签的 Access Token，不接受请求中的租户。
 *
 * @param tenantId 操作所在租户，所有读写都限定在该租户内
 * @param membershipId 操作者的 Membership，用于校验管理权限
 * @param identityId 操作者的 Identity，写入标准列 {@code created_by}、{@code updated_by}
 */
public record TenantActor(UUID tenantId, UUID membershipId, UUID identityId) {
    public static TenantActor of(Jwt jwt) {
        return new TenantActor(
                UUID.fromString(jwt.getClaimAsString("tenant_id")),
                UUID.fromString(jwt.getClaimAsString("membership_id")),
                UUID.fromString(jwt.getSubject()));
    }
}
