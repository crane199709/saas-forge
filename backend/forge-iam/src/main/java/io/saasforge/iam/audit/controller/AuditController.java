package io.saasforge.iam.audit.controller;

import io.saasforge.iam.audit.model.AuditQuery;
import io.saasforge.iam.audit.model.AuditRecord;
import io.saasforge.iam.audit.service.AuditService;
import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.web.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

// /audit/** 只接受 Tenant Realm Token，租户取自 Token；/platform/audit/** 只接受 Platform Realm Token。两者查询不同 Realm 的事件。
@Tag(name = "审计")
@RestController
class AuditController {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> DETAIL = new TypeReference<>() {};

    private final AuditService audits;

    AuditController(AuditService audits) {
        this.audits = audits;
    }

    @Operation(summary = "分页查询当前租户的审计记录，按发生时间倒序；可按时间、操作人、动作与对象筛选")
    @GetMapping("/audit/events")
    ApiResponse<List<AuditEventResponse>> tenantEvents(
            @AuthenticationPrincipal Jwt jwt,
            PageQuery page,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) @Size(max = 120) String actor,
            @RequestParam(required = false) @Size(max = 128) String action,
            @RequestParam(required = false) @Size(max = 64) String resourceType,
            @RequestParam(required = false) @Size(max = 255) String resourceId) {
        TenantActor tenantActor = TenantActor.of(jwt);
        AuditQuery query = query(from, to, actor, action, resourceType, resourceId);
        return ApiResponse.page(
                audits.searchTenant(tenantActor, query, page.offset(), page.size()).stream()
                        .map(AuditEventResponse::from)
                        .toList(),
                page.toPageMeta(audits.countTenant(tenantActor, query)));
    }

    @Operation(summary = "分页查询 Platform Realm 的审计记录，按发生时间倒序；可按时间、操作人、动作与对象筛选")
    @GetMapping("/platform/audit/events")
    ApiResponse<List<AuditEventResponse>> platformEvents(
            @AuthenticationPrincipal Jwt jwt,
            PageQuery page,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) @Size(max = 120) String actor,
            @RequestParam(required = false) @Size(max = 128) String action,
            @RequestParam(required = false) @Size(max = 64) String resourceType,
            @RequestParam(required = false) @Size(max = 255) String resourceId) {
        UUID operatorId = UUID.fromString(jwt.getSubject());
        AuditQuery query = query(from, to, actor, action, resourceType, resourceId);
        return ApiResponse.page(
                audits.searchPlatform(operatorId, query, page.offset(), page.size()).stream()
                        .map(AuditEventResponse::from)
                        .toList(),
                page.toPageMeta(audits.countPlatform(operatorId, query)));
    }

    private static AuditQuery query(
            OffsetDateTime from,
            OffsetDateTime to,
            String actor,
            String action,
            String resourceType,
            String resourceId) {
        return new AuditQuery(
                from, to, blankToNull(actor), blankToNull(action), blankToNull(resourceType), blankToNull(resourceId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * @param category {@code login}、{@code permission} 或 {@code operation}
     * @param actorAccount 操作人账号；操作人未知或已删除时为 null
     * @param outcome {@code success} 或 {@code failure}
     * @param errorCode 失败时的错误码
     */
    record AuditEventResponse(
            UUID id,
            OffsetDateTime occurredAt,
            String category,
            String action,
            UUID actorId,
            String actorAccount,
            String actorName,
            String resourceType,
            String resourceId,
            String outcome,
            String errorCode,
            Map<String, Object> detail,
            String service,
            String traceId) {
        static AuditEventResponse from(AuditRecord record) {
            return new AuditEventResponse(
                    record.id(),
                    record.occurredAt(),
                    record.category(),
                    record.action(),
                    record.actorId(),
                    record.actorAccount(),
                    record.actorName(),
                    record.resourceType(),
                    record.resourceId(),
                    record.outcome(),
                    record.errorCode(),
                    JSON.readValue(record.detail(), DETAIL),
                    record.service(),
                    record.traceId());
        }
    }
}
