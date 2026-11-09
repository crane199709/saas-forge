package io.saasforge.platform.plan.service;

import io.saasforge.platform.plan.model.TenantMemberImpactResponse;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.Collection;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 从 iam 读取 Plan 变更对租户成员的影响：成员数据属于 iam，platform 只知道 Plan 主数据。 */
@Service
public class TenantMemberImpactClient {
    private static final ParameterizedTypeReference<ApiResponse<TenantMemberImpactResponse>> RESPONSE =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;

    TenantMemberImpactClient(@Qualifier("iamRestClient") RestClient client) {
        this.client = client;
    }

    /**
     * 失去给定 Permission Code 与 Sub-App 时租户成员受到的影响。
     *
     * @throws ForgeException 503 {@code plan.impactUnavailable} iam 不可用或拒绝请求；不能以零影响代替，否则操作人会误以为降级无风险
     */
    public TenantMemberImpactResponse impact(
            UUID tenantId, Collection<String> lostPermissions, Collection<String> lostSubApps) {
        try {
            ApiResponse<TenantMemberImpactResponse> response = client.post()
                    .uri("/internal/tenants/{tenantId}/plan-impact", tenantId)
                    .body(new ImpactRequest(lostPermissions, lostSubApps))
                    .retrieve()
                    .body(RESPONSE);
            if (response == null || response.data() == null) {
                throw ForgeException.withErrorMessage(PlanErrorCode.IMPACT_UNAVAILABLE, "IAM returned no plan impact");
            }
            return response.data();
        } catch (RestClientException error) {
            throw ForgeException.withErrorMessage(
                    PlanErrorCode.IMPACT_UNAVAILABLE, "Failed to read plan impact: " + error.getMessage());
        }
    }

    private record ImpactRequest(Collection<String> lostPermissions, Collection<String> lostSubApps) {}
}
