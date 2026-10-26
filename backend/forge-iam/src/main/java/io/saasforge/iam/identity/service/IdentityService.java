package io.saasforge.iam.identity.service;

import io.saasforge.iam.identity.mapper.IdentityMapper;
import io.saasforge.iam.identity.model.ActiveMembership;
import io.saasforge.iam.identity.model.LoginCredential;
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

    public ActiveMembership findForRefresh(UUID identityId, UUID membershipId, UUID tenantId) {
        return identities.findForRefresh(identityId, membershipId, tenantId);
    }
}
