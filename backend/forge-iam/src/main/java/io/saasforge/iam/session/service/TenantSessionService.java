package io.saasforge.iam.session.service;

import io.saasforge.iam.identity.model.ActiveMembership;
import io.saasforge.iam.identity.model.LoginCredential;
import io.saasforge.iam.identity.service.IdentityService;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class TenantSessionService {
    private final IdentityService identities;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    TenantSessionService(IdentityService identities, PasswordEncoder passwords) {
        this.identities = identities;
        this.passwords = passwords;
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    // 只认证全局身份；没有或有多个有效 Membership 由调用方在密码校验通过后再处理。
    public Authentication authenticate(String account, String password) {
        LoginCredential credential = identities.findCredential(account);
        String hash = credential == null ? dummyHash : credential.passwordHash();
        boolean valid = passwords.matches(password, hash);
        if (!valid || credential == null) {
            throw ForgeException.withErrorMessage(SessionErrorCode.AUTH_FAILED, "Bad credentials");
        }
        // 会话只保留身份标识，不序列化数据库投影或密码哈希。
        var principal = User.withUsername(credential.identityId().toString())
                .password("")
                .authorities("TENANT")
                .build();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    public List<ActiveMembership> memberships(UUID identityId) {
        return identities.findActiveMemberships(identityId);
    }
}
