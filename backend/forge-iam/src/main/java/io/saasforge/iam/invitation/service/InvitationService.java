package io.saasforge.iam.invitation.service;

import io.saasforge.iam.identity.model.IdentityStatus;
import io.saasforge.iam.identity.service.IdentityService;
import io.saasforge.iam.invitation.config.InvitationProperties;
import io.saasforge.iam.invitation.model.AcceptedInvitation;
import io.saasforge.iam.invitation.model.InvitationPreview;
import io.saasforge.iam.membership.model.Invitation;
import io.saasforge.iam.membership.model.MembershipStatus;
import io.saasforge.iam.membership.service.MembershipService;
import io.saasforge.iam.password.service.PasswordPolicy;
import io.saasforge.iam.registration.service.RegistrationErrorCode;
import io.saasforge.iam.verification.service.ContactSender;
import io.saasforge.iam.verification.service.InvitationMessage;
import io.saasforge.sdk.core.api.ForgeException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 邀请身份加入租户：发出带一次性令牌的链接，接受后 Membership 由 invited 变为 active，待激活身份同时设置凭证。
 *
 * <p>令牌只在 Redis 中保存其 SHA-256，有效期即键的 TTL；拿到 Redis 数据也无法还原可用的链接。持有链接即证明能收到该邮箱或手机的消息，
 * 因此接受邀请不要求先登录。
 */
@Service
public class InvitationService {
    // 认证前没有可信的租户标识，键沿用 iam:global: 前缀。
    private static final String PREFIX = "iam:global:invitation:";

    private final MembershipService memberships;
    private final IdentityService identities;
    private final ContactSender sender;
    private final PasswordEncoder passwords;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transaction;
    private final InvitationProperties properties;
    private final PasswordPolicy policy;
    private final SecureRandom random = new SecureRandom();

    InvitationService(
            MembershipService memberships,
            IdentityService identities,
            ContactSender sender,
            PasswordEncoder passwords,
            StringRedisTemplate redis,
            TransactionTemplate transaction,
            InvitationProperties properties,
            PasswordPolicy policy) {
        this.memberships = memberships;
        this.identities = identities;
        this.sender = sender;
        this.passwords = passwords;
        this.redis = redis;
        this.transaction = transaction;
        this.properties = properties;
        this.policy = policy;
    }

    /**
     * 向联系方式发送加入租户的邀请。重复调用会再发一封，之前的链接在过期前仍然有效。
     *
     * @param contact 被邀请人的邮箱或手机号
     * @param membership 被邀请人在该租户的 Membership，须为 invited
     * @param requiresCredentials 被邀请人是否为待激活身份
     */
    public void invite(String contact, String tenantName, MembershipStatus membership, boolean requiresCredentials) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(key(token), membership.membershipId().toString(), properties.ttl());
        String link = UriComponentsBuilder.fromUriString(properties.acceptUrl())
                .queryParam("invitation", token)
                .build()
                .toUriString();
        sender.send(contact, new InvitationMessage(tenantName, link, properties.ttl(), requiresCredentials));
    }

    public InvitationPreview preview(String token) {
        Invitation invitation = find(token);
        return new InvitationPreview(invitation.tenantName(), requiresCredentials(invitation));
    }

    /**
     * 接受邀请。待激活身份必须提供账号与密码，已有凭证的身份忽略这些参数。成功后令牌作废，被邀请人照常登录进入该租户。
     *
     * @throws ForgeException 404 {@code invitation.invalid}；400 {@code invitation.credentialsRequired} 或 {@code password.*}；
     *     409 {@code registration.accountTaken}
     */
    public AcceptedInvitation accept(String token, String account, String password, String displayName) {
        Invitation invitation = find(token);
        boolean activateIdentity = requiresCredentials(invitation);
        if (activateIdentity && (account == null || password == null)) {
            throw ForgeException.withErrorMessage(
                    InvitationErrorCode.CREDENTIALS_REQUIRED, "Pending identity must set account and password");
        }
        if (activateIdentity && identities.existsAccount(account)) {
            throw accountTaken();
        }
        // 为待激活身份设置密码等同注册，执行同一 Password Policy。
        if (activateIdentity) {
            policy.checkIdentity(password, account);
        }
        try {
            transaction.executeWithoutResult(status -> {
                // 两步都以原状态为条件：并发接受同一邀请时只有一方成功，另一方整体回滚。
                if (activateIdentity
                        && !identities.activatePending(
                                invitation.identityId(),
                                account,
                                displayName == null || displayName.isBlank() ? account : displayName,
                                passwords.encode(password))) {
                    throw invalid();
                }
                if (!memberships.activateInvited(invitation.membershipId())) {
                    throw invalid();
                }
            });
        } catch (DuplicateKeyException race) {
            throw accountTaken();
        }
        redis.delete(key(token));
        return new AcceptedInvitation(invitation.tenantId(), invitation.tenantName());
    }

    private Invitation find(String token) {
        String membershipId = redis.opsForValue().get(key(token));
        return memberships
                .findInvitation(membershipId == null ? null : UUID.fromString(membershipId))
                .filter(invitation -> MembershipStatus.INVITED.equals(invitation.membershipStatus()))
                .orElseThrow(InvitationService::invalid);
    }

    private static boolean requiresCredentials(Invitation invitation) {
        return IdentityStatus.PENDING.equals(invitation.identityStatus());
    }

    private static String key(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    private static ForgeException invalid() {
        return ForgeException.withErrorMessage(InvitationErrorCode.INVALID, "Invitation is invalid or expired");
    }

    private static ForgeException accountTaken() {
        return ForgeException.withErrorMessage(RegistrationErrorCode.ACCOUNT_TAKEN, "Identity already exists");
    }
}
