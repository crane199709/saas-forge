package io.saasforge.iam.organization.controller;

import io.saasforge.iam.organization.model.DepartmentRow;
import io.saasforge.iam.organization.model.TenantActor;
import io.saasforge.iam.organization.service.DepartmentService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "部门")
@RestController
@RequestMapping("/org/departments")
class DepartmentController {
    private final DepartmentService departments;

    DepartmentController(DepartmentService departments) {
        this.departments = departments;
    }

    @Operation(summary = "列出当前租户的全部部门，按路径排序，由调用方组装为树")
    @GetMapping
    ApiResponse<List<DepartmentResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(departments.list(TenantActor.of(jwt)).stream()
                .map(DepartmentResponse::from)
                .toList());
    }

    @Operation(summary = "新建部门，不指定上级时为根部门")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<DepartmentResponse> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateDepartmentRequest request) {
        return ApiResponse.created(DepartmentResponse.from(
                departments.create(TenantActor.of(jwt), request.name().strip(), request.parentId())));
    }

    @Operation(summary = "部门改名")
    @PutMapping("/{id}")
    ApiResponse<DepartmentResponse> rename(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody RenameRequest request) {
        return ApiResponse.ok(DepartmentResponse.from(
                departments.rename(TenantActor.of(jwt), id, request.name().strip())));
    }

    @Operation(summary = "把部门连同子树移到新的上级之下，上级为空时移为根部门")
    @PutMapping("/{id}/parent")
    ApiResponse<DepartmentResponse> move(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody MoveRequest request) {
        return ApiResponse.ok(DepartmentResponse.from(departments.move(TenantActor.of(jwt), id, request.parentId())));
    }

    @Operation(summary = "删除部门；有子部门或成员时拒绝")
    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        departments.delete(TenantActor.of(jwt), id);
        return ApiResponse.ok();
    }

    record CreateDepartmentRequest(
            @NotBlank @Size(max = 120) String name, UUID parentId) {}

    record RenameRequest(@NotBlank @Size(max = 120) String name) {}

    record MoveRequest(UUID parentId) {}

    record DepartmentResponse(UUID id, UUID parentId, String name, String path) {
        static DepartmentResponse from(DepartmentRow row) {
            return new DepartmentResponse(row.id(), row.parentId(), row.name(), row.path());
        }
    }
}
