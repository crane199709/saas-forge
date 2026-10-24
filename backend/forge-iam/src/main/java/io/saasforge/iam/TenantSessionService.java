package io.saasforge.iam;

import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
class TenantSessionService {
    private final IdentityMapper identities;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    TenantSessionService(IdentityMapper identities, PasswordEncoder passwords) {
        this.identities = identities;
        this.passwords = passwords;
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    Authentication authenticate(String account, String password) {
        List<LoginIdentity> matches = identities.findForLogin(account);
        String hash = matches.isEmpty() ? dummyHash : matches.get(0).passwordHash();
        boolean valid = passwords.matches(password, hash);
        if (!valid || matches.size() != 1) {
            throw new BadCredentialsException("auth.failed");
        }
        // 会话只保留身份标识，不序列化数据库投影或密码哈希。
        var principal = User.withUsername(matches.get(0).identityId().toString())
                .password("")
                .authorities("TENANT")
                .build();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }
}
