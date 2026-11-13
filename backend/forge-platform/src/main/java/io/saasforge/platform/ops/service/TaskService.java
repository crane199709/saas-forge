package io.saasforge.platform.ops.service;

import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.scheduling.TaskDefinitionResponse;
import io.saasforge.sdk.scheduling.TaskErrorCode;
import io.saasforge.sdk.scheduling.TaskRun;
import io.saasforge.sdk.scheduling.TaskTriggerResponse;
import io.saasforge.sdk.security.OperatorContext;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

@Service
public class TaskService {
    private static final ParameterizedTypeReference<ApiResponse<TaskTriggerResponse>> TRIGGER =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<List<TaskDefinitionResponse>>> DEFINITIONS =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<List<TaskRun>>> RUNS =
            new ParameterizedTypeReference<>() {};
    private final OpsClient client;
    private final AuditRecorder audit;

    TaskService(OpsClient client, AuditRecorder audit) {
        this.client = client;
        this.audit = audit;
    }

    public ApiResponse<List<TaskDefinitionResponse>> list(String service) {
        return client.get(service, "/tasks", DEFINITIONS);
    }

    public ApiResponse<List<TaskRun>> runs(String service, String name, String view) {
        if (!List.of("recent", "problems").contains(view)) {
            throw new ForgeException(FrameworkErrorCode.REQUEST_INVALID);
        }
        return client.get(
                service,
                "/tasks/" + UriUtils.encodePathSegment(name, StandardCharsets.UTF_8) + "/runs?view=" + view,
                RUNS);
    }

    public ApiResponse<TaskTriggerResponse> trigger(String service, String name, UUID targetTenantId) {
        UUID operator = OperatorContext.current().operatorId();
        var event = AuditEvent.builder(
                        AuditRealm.PLATFORM,
                        AuditCategory.OPERATION,
                        targetTenantId == null ? "ops.taskTrigger" : "ops.taskTenantRetry")
                .actor(operator)
                .resource("task", name)
                .detail("service", service)
                .detail("targetTenantId", targetTenantId);
        try {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("operatorId", operator);
            if (targetTenantId != null) {
                command.put("targetTenantId", targetTenantId);
            }
            var response = client.post(
                    service,
                    "/tasks/" + UriUtils.encodePathSegment(name, StandardCharsets.UTF_8) + "/trigger",
                    command,
                    TRIGGER);
            if (!ApiResponse.OK.equals(response.code())) {
                throw rejected(response.code());
            }
            if (response.data() == null || response.data().runId() == null) {
                throw new ForgeException(OpsErrorCode.SERVICE_UNAVAILABLE);
            }
            event.detail("runId", response.data().runId());
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
        if (FrameworkErrorCode.REQUEST_INVALID.code().equals(code)) {
            return new ForgeException(FrameworkErrorCode.REQUEST_INVALID);
        }
        for (TaskErrorCode error : TaskErrorCode.values()) {
            if (error.code().equals(code)) {
                return new ForgeException(error);
            }
        }
        return new ForgeException(OpsErrorCode.SERVICE_UNAVAILABLE);
    }
}
