package io.saasforge.iam.organization.controller;

import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.organization.model.MemberAssignment;
import io.saasforge.iam.organization.service.AssignmentService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.web.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "成员组织分配")
@RestController
@RequestMapping("/org/memberships")
class AssignmentController {
    private final AssignmentService assignments;

    AssignmentController(AssignmentService assignments) {
        this.assignments = assignments;
    }

    @Operation(summary = "分页列出当前租户的成员及其部门、岗位与扩展属性")
    @GetMapping
    ApiResponse<List<MemberAssignmentResponse>> list(@AuthenticationPrincipal Jwt jwt, PageQuery query) {
        TenantActor actor = TenantActor.of(jwt);
        return ApiResponse.page(
                assignments.page(actor, query.offset(), query.size()).stream()
                        .map(MemberAssignmentResponse::from)
                        .toList(),
                query.toPageMeta(assignments.count(actor)));
    }

    @Operation(summary = "整体替换成员的主部门、兼任部门、岗位与扩展属性值")
    @PutMapping("/{membershipId}/assignment")
    ApiResponse<Void> assign(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID membershipId, @RequestBody AssignmentRequest request) {
        assignments.assign(
                TenantActor.of(jwt),
                membershipId,
                request.primaryDepartmentId(),
                request.departmentIds(),
                request.positionIds(),
                request.attributes());
        return ApiResponse.ok();
    }

    record MemberAssignmentResponse(
            UUID membershipId,
            String account,
            String displayName,
            String status,
            UUID primaryDepartmentId,
            List<UUID> departmentIds,
            List<UUID> positionIds,
            Map<String, Object> attributes) {
        static MemberAssignmentResponse from(MemberAssignment value) {
            return new MemberAssignmentResponse(
                    value.membershipId(),
                    value.account(),
                    value.displayName(),
                    value.status(),
                    value.primaryDepartmentId(),
                    value.departmentIds(),
                    value.positionIds(),
                    value.attributes());
        }
    }

    /**
     * @param primaryDepartmentId 主部门；为空时兼任部门也必须为空
     * @param departmentIds 兼任部门，不含主部门
     */
    record AssignmentRequest(
            UUID primaryDepartmentId,
            List<UUID> departmentIds,
            List<UUID> positionIds,
            Map<String, Object> attributes) {
        AssignmentRequest {
            departmentIds = departmentIds == null ? List.of() : departmentIds;
            positionIds = positionIds == null ? List.of() : positionIds;
            attributes = attributes == null ? Map.of() : attributes;
        }
    }
}
