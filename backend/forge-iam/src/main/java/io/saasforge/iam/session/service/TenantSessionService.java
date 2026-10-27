package io.saasforge.iam.session.service;

import io.saasforge.iam.identity.model.ActiveMembership;
import io.saasforge.iam.identity.model.LoginCredential;
import io.saasforge.iam.identity.service.IdentityService;
import io.saasforge.iam.verification.model.CodeDelivery;
import io.saasforge.iam.verification.service.VerificationCodeService;
import io.saasforge.iam.verification.service.VerificationPurpose;
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
    private final VerificationCodeService verifications;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    TenantSessionService(IdentityService identities, VerificationCodeService verifications, PasswordEncoder passwords) {
        this.identities = identities;
        this.verifications = verifications;
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
        return authenticated(credential.identityId());
    }

    // 未注册的目标同样生成验证码、计入发送间隔，只是不发送，响应与校验结果因此与已注册目标一致。
    public CodeDelivery sendLoginCode(String target) {
        boolean registered = identities.findActiveIdByContact(target).isPresent();
        return verifications.issue(VerificationPurpose.LOGIN, target, registered);
    }

    // 与密码登录建立同样的会话主体，之后的授权码与 Token 流程完全相同。
    public Authentication authenticateByCode(String target, String code) {
        verifications.verify(VerificationPurpose.LOGIN, target, code);
        // 发码后身份被停用或删除时才会找不到。
        UUID identityId = identities
                .findActiveIdByContact(target)
                .orElseThrow(
                        () -> ForgeException.withErrorMessage(SessionErrorCode.AUTH_FAILED, "Identity unavailable"));
        return authenticated(identityId);
    }

    private static Authentication authenticated(UUID identityId) {
        // 会话只保留身份标识，不序列化数据库投影或密码哈希。
        var principal = User.withUsername(identityId.toString())
                .password("")
                .authorities("TENANT")
                .build();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    public List<ActiveMembership> memberships(UUID identityId) {
        return identities.findActiveMemberships(identityId);
    }
}
