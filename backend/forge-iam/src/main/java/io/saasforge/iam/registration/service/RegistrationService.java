package io.saasforge.iam.registration.service;

import io.saasforge.iam.identity.service.IdentityService;
import io.saasforge.iam.password.service.PasswordPolicy;
import io.saasforge.iam.verification.model.CodeDelivery;
import io.saasforge.iam.verification.service.VerificationCodeService;
import io.saasforge.iam.verification.service.VerificationPurpose;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

// 注册只创建全局 Identity，不建立任何 Membership；加入租户由邀请或创建租户完成。
@Service
public class RegistrationService {
    private final IdentityService identities;
    private final VerificationCodeService verifications;
    private final PasswordEncoder passwords;
    private final PasswordPolicy policy;

    RegistrationService(
            IdentityService identities,
            VerificationCodeService verifications,
            PasswordEncoder passwords,
            PasswordPolicy policy) {
        this.identities = identities;
        this.verifications = verifications;
        this.passwords = passwords;
        this.policy = policy;
    }

    // 注册会如实提示邮箱或手机号已占用：提交注册时同样要提示，在发送前隐藏没有意义。
    public CodeDelivery sendCode(String target) {
        if (identities.existsContact(target)) {
            throw conflict(RegistrationErrorCode.CONTACT_TAKEN);
        }
        return verifications.issue(VerificationPurpose.REGISTER, target, true);
    }

    public UUID register(String account, String password, String displayName, String target, String code) {
        // 先查重与校验密码再校验验证码，账号重复或密码不合规时不消费验证码，用户修改后可继续使用。
        ensureAvailable(account, target);
        policy.checkIdentity(password, account, target);
        verifications.verify(VerificationPurpose.REGISTER, target, code);
        boolean email = VerificationCodeService.isEmail(target);
        try {
            return identities.register(
                    account,
                    email ? target : null,
                    email ? null : target,
                    displayName == null || displayName.isBlank() ? account : displayName,
                    passwords.encode(password));
        } catch (DuplicateKeyException race) {
            // 并发注册在查重之后抢先写入，重新查重以返回具体的冲突字段。
            ensureAvailable(account, target);
            throw race;
        }
    }

    private void ensureAvailable(String account, String target) {
        if (identities.existsAccount(account)) {
            throw conflict(RegistrationErrorCode.ACCOUNT_TAKEN);
        }
        if (identities.existsContact(target)) {
            throw conflict(RegistrationErrorCode.CONTACT_TAKEN);
        }
    }

    private static ForgeException conflict(RegistrationErrorCode code) {
        return ForgeException.withErrorMessage(code, "Identity already exists");
    }
}
