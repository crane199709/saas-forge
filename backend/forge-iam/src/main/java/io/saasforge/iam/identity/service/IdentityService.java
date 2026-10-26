package io.saasforge.iam.identity.service;

import io.saasforge.iam.identity.mapper.IdentityMapper;
import io.saasforge.iam.identity.model.LoginIdentity;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

// 其他功能包只能经由本服务读取身份数据，不直接注入 IdentityMapper。
@Service
public class IdentityService {
    private final IdentityMapper identities;

    IdentityService(IdentityMapper identities) {
        this.identities = identities;
    }

    public List<LoginIdentity> findForLogin(String account) {
        return identities.findForLogin(account);
    }

    public List<LoginIdentity> findForIdentity(UUID identityId) {
        return identities.findForIdentity(identityId);
    }

    public LoginIdentity findForRefresh(UUID identityId, UUID membershipId, UUID tenantId) {
        return identities.findForRefresh(identityId, membershipId, tenantId);
    }
}
