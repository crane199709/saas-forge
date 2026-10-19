package io.saas.forge.iam.application.authentication;

import io.saas.forge.iam.domain.session.DurableRevocation;
import io.saas.forge.iam.domain.session.AccessTokenIssuance;
import io.saas.forge.iam.domain.session.RevocationFence;
import io.saas.forge.iam.domain.session.RevocationFenceTarget;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Redis 撤销热路径；Ready 缺失或为未就绪时读取必须 fail closed。 */
public interface RevocationIndex {
    void revokeJti(UUID jti, Instant expiresAt, Instant at);

    /** OAuth Client 吊销不可逆，因此拒绝项不设置 TTL。 */
    void revokeClient(UUID clientId);

    /** 原子写入失陷 kid 及其全部未过期 jti，任一写入失败都不得部分报告成功。 */
    void revokeSigningKey(String kid, Instant rejectUntil, List<AccessTokenIssuance> issuances, Instant at);

    void markNotReady();

    void rebuild(
            List<DurableRevocation> revocations,
            List<RevocationFence> fences,
            List<UUID> revokedClientIds,
            Instant at);

    void establishFence(RevocationFence fence);

    /** 仅当 Redis 当前值仍是该 generation 时删除 Fence，返回 false 表示代际已变化。 */
    boolean releaseFence(RevocationFence fence);

    boolean isUserTokenFenced(RevocationFenceTarget target);

    default boolean isIdentityFenced(UUID identityId) { return false; }

    boolean isReady();

    boolean isJtiRevoked(UUID jti);

    boolean isKidRevoked(String kid);

    boolean isClientRevoked(UUID clientId);

    /** 原子检查 Ready、签名 kid 与 OAuth Client，状态不可判定时 fail closed。 */
    boolean isServiceTokenRevoked(UUID clientId, String kid);

    boolean isTokenRevoked(UUID jti, String kid);
}
