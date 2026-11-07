package io.saasforge.platform.ops.service;

import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.mq.DeadEventResponse;
import io.saasforge.sdk.security.OperatorContext;
import io.saasforge.sdk.web.PageQuery;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

@Service
public class DeadEventService {
    private static final ParameterizedTypeReference<ApiResponse<DeadEventResponse>> RESPONSE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<List<DeadEventResponse>>> PAGE =
            new ParameterizedTypeReference<>() {};
    private final OpsClient client;
    private final AuditRecorder audit;

    DeadEventService(OpsClient client, AuditRecorder audit) {
        this.client = client;
        this.audit = audit;
    }

    public ApiResponse<List<DeadEventResponse>> list(String service, String status, PageQuery page) {
        if (!List.of("PENDING", "REPLAYED", "ABANDONED").contains(status))
            throw new ForgeException(FrameworkErrorCode.REQUEST_INVALID);
        return client.get(
                service, "/dead-events?status=" + status + "&page=" + page.page() + "&size=" + page.size(), PAGE);
    }

    public ApiResponse<DeadEventResponse> get(String service, UUID id) {
        return client.get(service, "/dead-events/" + id, RESPONSE);
    }

    public ApiResponse<DeadEventResponse> replay(String service, UUID id, String reason) {
        return command(service, id, reason, "replay", "ops.deadEventReplay");
    }

    public ApiResponse<DeadEventResponse> abandon(String service, UUID id, String reason) {
        return command(service, id, reason, "abandon", "ops.deadEventAbandon");
    }

    private ApiResponse<DeadEventResponse> command(
            String service, UUID id, String reason, String command, String action) {
        var event = AuditEvent.builder(AuditRealm.PLATFORM, AuditCategory.OPERATION, action)
                .actor(OperatorContext.current().operatorId())
                .resource("deadEvent", id)
                .detail("service", service)
                .detail("reason", reason == null ? null : reason.strip());
        try {
            if (reason == null || reason.isBlank()) throw new ForgeException(OpsErrorCode.REASON_REQUIRED);
            var response = client.post(
                    service, "/dead-events/" + id + "/" + command, Map.of("reason", reason.strip()), RESPONSE);
            if (!ApiResponse.OK.equals(response.code())) throw rejected(response.code());
            if (response.data() != null) event.detail("eventId", response.data().eventId());
            return response;
        } catch (ForgeException error) {
            event.failure(error.errorCode().code());
            throw error;
        } catch (RuntimeException error) {
            event.failure(FrameworkErrorCode.SYSTEM_ERROR.code());
            throw error;
        } finally {
            audit.record(event.build());
        }
    }

    private static ForgeException rejected(String code) {
        return new ForgeException(
                switch (code) {
                    case "deadEvent.notFound" -> OpsErrorCode.DEAD_EVENT_NOT_FOUND;
                    case "deadEvent.alreadyHandled" -> OpsErrorCode.DEAD_EVENT_ALREADY_HANDLED;
                    case "deadEvent.reasonRequired" -> OpsErrorCode.REASON_REQUIRED;
                    case "deadEvent.replayFailed" -> OpsErrorCode.DEAD_EVENT_REPLAY_FAILED;
                    default -> OpsErrorCode.SERVICE_UNAVAILABLE;
                });
    }
}
