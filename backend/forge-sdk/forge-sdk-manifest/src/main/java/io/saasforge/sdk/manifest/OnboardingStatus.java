package io.saasforge.sdk.manifest;

import java.time.Instant;

/** 本实例的上报快照；不是整个服务集群的汇总，不包含任何凭证或内部地址。 */
public record OnboardingStatus(
        boolean enabled,
        String reportedVersion,
        Instant lastSuccessAt,
        ManifestFailure lastFailure,
        Instant lastFailureAt,
        int attempts,
        String phase) {}
