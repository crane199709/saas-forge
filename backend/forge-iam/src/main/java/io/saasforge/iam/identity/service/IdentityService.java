package io.saasforge.iam.identity.service;

import io.saasforge.iam.identity.mapper.IdentityMapper;
import io.saasforge.iam.identity.model.ActiveMembership;
import io.saasforge.iam.identity.model.LoginCredential;
import io.saasforge.sdk.core.UuidV7;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

// 其他功能包只能经由本服务读取身份数据，不直接注入 IdentityMapper。
@Service
public class IdentityService {
    private final IdentityMapper identities;

    IdentityService(IdentityMapper identities) {
        this.identities = identities;
    }

    public LoginCredential findCredential(String account) {
        return identities.findCredential(account);
    }

    public List<ActiveMembership> findActiveMemberships(UUID identityId) {
        return identities.findActiveMemberships(identityId);
    }

    // 目标 Membership 只在已认证身份自己的有效 Membership 中查找，客户端无法借此进入他人或停用的租户。
    public Optional<ActiveMembership> findActiveMembership(UUID identityId, UUID membershipId) {
        return identities.findActiveMemberships(identityId).stream()
                .filter(membership -> membership.membershipId().equals(membershipId))
                .findFirst();
    }

    public Optional<UUID> findActiveIdByContact(String target) {
        return Optional.ofNullable(identities.findActiveIdByContact(target)).map(UUID::fromString);
    }

    public boolean existsAccount(String account) {
        return identities.existsAccount(account);
    }

    public boolean existsContact(String target) {
        return identities.existsContact(target);
    }

    /** 注册身份，邮箱与手机号恰好填写其一；违反唯一索引时抛出 Spring 的 {@code DuplicateKeyException}。 */
    public UUID register(String account, String email, String phone, String displayName, String passwordHash) {
        UUID id = UuidV7.generate();
        identities.insert(id, account, email, phone, displayName, passwordHash);
        return id;
    }

    public ActiveMembership findForRefresh(UUID identityId, UUID membershipId, UUID tenantId) {
        return identities.findForRefresh(identityId, membershipId, tenantId);
    }
}
