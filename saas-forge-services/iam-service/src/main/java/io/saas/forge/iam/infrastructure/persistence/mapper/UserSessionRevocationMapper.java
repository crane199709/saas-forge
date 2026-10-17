package io.saas.forge.iam.infrastructure.persistence.mapper;

import io.saas.forge.iam.infrastructure.persistence.record.UserSessionRevocationCandidateRow;
import io.saas.forge.iam.infrastructure.persistence.record.UserSessionRevocationRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface UserSessionRevocationMapper {
    UserSessionRevocationRow find(@Param("requestId") UUID requestId);
    int insert(@Param("requestId") UUID requestId, @Param("at") OffsetDateTime at);
    UserSessionRevocationRow claim(@Param("requestId") UUID requestId, @Param("claimant") String claimant,
            @Param("now") OffsetDateTime now, @Param("leaseUntil") OffsetDateTime leaseUntil,
            @Param("maximumAttempts") int maximumAttempts);
    UserSessionRevocationRow claimNext(@Param("claimant") String claimant, @Param("now") OffsetDateTime now,
            @Param("leaseUntil") OffsetDateTime leaseUntil, @Param("maximumAttempts") int maximumAttempts);
    int exhaustExpiredAtLimit(@Param("now") OffsetDateTime now,
            @Param("maximumAttempts") int maximumAttempts, @Param("failure") String failure);
    List<UserSessionRevocationCandidateRow> findCandidates(
            @Param("targetType") String targetType, @Param("tenantId") UUID tenantId,
            @Param("membershipId") UUID membershipId, @Param("identityId") UUID identityId,
            @Param("cursor") UUID cursor,
            @Param("at") OffsetDateTime at, @Param("batchSize") int batchSize);
    int revokeFamilies(@Param("familyIds") List<UUID> familyIds, @Param("at") OffsetDateTime at);
    int revokeIssuances(@Param("jtis") List<UUID> jtis, @Param("at") OffsetDateTime at);
    int advance(@Param("requestId") UUID requestId, @Param("fencingToken") long fencingToken,
            @Param("cursor") UUID cursor, @Param("familyCount") long familyCount,
            @Param("jtiCount") long jtiCount, @Param("completed") boolean completed,
            @Param("at") OffsetDateTime at);
    int scheduleRetry(@Param("requestId") UUID requestId, @Param("fencingToken") long fencingToken,
            @Param("retryAt") OffsetDateTime retryAt, @Param("failure") String failure);
    int exhaust(@Param("requestId") UUID requestId, @Param("fencingToken") long fencingToken,
            @Param("at") OffsetDateTime at, @Param("failure") String failure);
    int recover(@Param("requestId") UUID requestId, @Param("at") OffsetDateTime at);
    UserSessionRevocationRow findRelease(@Param("releaseRequestId") UUID releaseRequestId);
    int insertRelease(@Param("releaseRequestId") UUID releaseRequestId,
            @Param("revocationRequestId") UUID revocationRequestId, @Param("targetType") String targetType,
            @Param("targetId") UUID targetId, @Param("at") OffsetDateTime at);
}
