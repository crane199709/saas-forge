package io.saasforge.iam.todo.controller;

import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.todo.model.TodoResponse;
import io.saasforge.iam.todo.service.TodoService;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.web.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TodoController {
    private final TodoService todos;

    TodoController(TodoService todos) {
        this.todos = todos;
    }

    @Operation(summary = "分页列出当前成员可处理的待办，按当前权限和套餐即时裁剪")
    @GetMapping("/todos")
    ApiResponse<List<TodoResponse>> page(@AuthenticationPrincipal Jwt jwt, PageQuery query) {
        TenantActor actor = TenantActor.of(jwt);
        // IAM 的全局表不绑定租户，待办的 RLS 连接必须在服务事务开始前明确绑定。
        try (var ignored = CurrentTenant.bind(actor.tenantId())) {
            var page = todos.page(actor, query);
            return ApiResponse.page(page.items(), query.toPageMeta(page.total()));
        }
    }
}
