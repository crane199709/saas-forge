package io.saas.forge.iam.application.bootstrap;

import io.saas.forge.iam.domain.client.ClientSecretDigest;
import io.saas.forge.iam.domain.client.OAuthClient;
import io.saas.forge.iam.domain.client.OAuthClientBootstrapState;
import io.saas.forge.iam.domain.client.OAuthClientRepository;
import io.saas.forge.iam.domain.shared.Sha256Digest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

public class ReservedServiceClientBootstrapService {
    private final OAuthClientRepository clients;
    private final Clock clock;

    public ReservedServiceClientBootstrapService(OAuthClientRepository clients, Clock clock) {
        this.clients = clients;
        this.clock = clock;
    }

    /** 提交的 Client 必须在同一事务中完成创建或严格重放校验。 */
    @Transactional
    public ReservedServiceClientBootstrapResult bootstrap(List<ReservedServiceClientBootstrapInput> inputs) {
        validateCompleteSet(inputs);
        clients.lockReservedClientBootstrap();
        Instant initializedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        EnumMap<ReservedServiceClient, ReservedServiceClientBootstrapResult.ClientResult> results =
                new EnumMap<>(ReservedServiceClient.class);
        for (ReservedServiceClientBootstrapInput input : inputs) {
            Sha256Digest digest = ClientSecretDigest.fromPlaintext(input.clientSecret());
            var existing = clients.findBootstrapState(input.clientId());
            if (existing.isPresent()) {
                requireCurrentMatch(input, digest, existing.get(), initializedAt);
                results.put(input.service(), new ReservedServiceClientBootstrapResult.ClientResult(
                        input.clientId(), ReservedServiceClientBootstrapResult.Outcome.ALREADY_INITIALIZED));
                continue;
            }
            clients.findAnyByReservedServiceKey(input.service().serviceKey()).ifPresent(found -> {
                ReservedServiceClientBootstrapConflictException.Reason reason =
                        found.status() == io.saas.forge.iam.domain.client.OAuthClientStatus.REVOKED
                                ? ReservedServiceClientBootstrapConflictException.Reason.CLIENT_REVOKED
                                : ReservedServiceClientBootstrapConflictException.Reason.CLIENT_CONFIGURATION_MISMATCH;
                throw new ReservedServiceClientBootstrapConflictException(input.service(), reason);
            });
            OAuthClient client = OAuthClient.register(
                    input.service().displayName(), input.service().allowedScopes(), initializedAt)
                    .identifiedBy(input.clientId());
            clients.createWithId(client, digest, initializedAt);
            results.put(input.service(), new ReservedServiceClientBootstrapResult.ClientResult(
                    input.clientId(), ReservedServiceClientBootstrapResult.Outcome.INITIALIZED));
        }
        return new ReservedServiceClientBootstrapResult(results);
    }

    private static void validateCompleteSet(List<ReservedServiceClientBootstrapInput> inputs) {
        if (inputs == null || (inputs.size() != 3 && inputs.size() != 4)) {
            throw new IllegalArgumentException("保留 OAuth Client 引导必须提供原有三个服务身份，可追加 Remote Delivery 身份");
        }
        EnumSet<ReservedServiceClient> services = EnumSet.noneOf(ReservedServiceClient.class);
        HashSet<java.util.UUID> ids = new HashSet<>();
        HashSet<Sha256Digest> digests = new HashSet<>();
        for (ReservedServiceClientBootstrapInput input : inputs) {
            if (input == null
                    || !services.add(input.service())
                    || !ids.add(input.clientId())
                    || !digests.add(ClientSecretDigest.fromPlaintext(input.clientSecret()))) {
                throw new IllegalArgumentException("保留 OAuth Client 的服务、ID 和 Secret 必须各不相同");
            }
        }
        EnumSet<ReservedServiceClient> required = EnumSet.of(ReservedServiceClient.IAM,
                ReservedServiceClient.TENANT_ACCESS, ReservedServiceClient.ENTITLEMENT);
        if (!services.equals(required) && !services.equals(EnumSet.allOf(ReservedServiceClient.class))) {
            throw new IllegalArgumentException("保留 OAuth Client 引导服务集合不完整");
        }
    }

    private static void requireCurrentMatch(
            ReservedServiceClientBootstrapInput input,
            Sha256Digest digest,
            OAuthClientBootstrapState state,
            Instant at) {
        if (state.client().status() == io.saas.forge.iam.domain.client.OAuthClientStatus.REVOKED) {
            throw new ReservedServiceClientBootstrapConflictException(
                    input.service(), ReservedServiceClientBootstrapConflictException.Reason.CLIENT_REVOKED);
        }
        boolean matches = state.client().id().equals(input.clientId())
                && state.client().displayName().equals(input.service().displayName())
                && state.client().clientType() == io.saas.forge.iam.domain.client.OAuthClientType.RESERVED_SERVICE
                && state.client().reservedServiceKey() == input.service().serviceKey()
                && state.client().allowedScopes().equals(input.service().allowedScopes())
                && state.client().revokedAt() == null;
        if (!matches) {
            throw new ReservedServiceClientBootstrapConflictException(input.service());
        }
        if (!state.hasCurrentSecret(digest, at)) {
            throw new ReservedServiceClientBootstrapConflictException(
                    input.service(), ReservedServiceClientBootstrapConflictException.Reason.MOUNTED_SECRET_NOT_CURRENT);
        }
    }
}
