package io.saasforge.platform.onboarding.model;

import io.saasforge.sdk.manifest.OnboardingStatus;
import java.time.Instant;
import java.util.List;

/** 三份独立的当次读取证据，不能相互推导通过状态。 */
public record OnboardingDiagnostics(boolean directoryAvailable, List<ServiceDiagnostic> services) {
    public record ServiceDiagnostic(
            String service, ConfigurationEvidence configuration, RuntimeEvidence runtime, RequestEvidence request) {}

    public record ConfigurationEvidence(
            String status, String source, Instant checkedAt, String prefix, List<String> missing) {}

    public record RuntimeEvidence(String status, Instant checkedAt, OnboardingStatus reporting) {}

    public record RequestEvidence(String status, Instant checkedAt, String detail) {}
}
