package io.saasforge.iam.todo.service;

import io.saasforge.iam.membership.model.MembershipStatus;
import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.membership.service.MembershipService;
import io.saasforge.iam.permission.service.IamPermissions;
import io.saasforge.iam.plan.service.PlanService;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.iam.todo.mapper.TodoMapper;
import io.saasforge.iam.todo.model.TodoPage;
import io.saasforge.iam.todo.model.TodoResponse;
import io.saasforge.iam.todo.model.TodoVisibility;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.notification.TodoClosed;
import io.saasforge.sdk.notification.TodoOpened;
import io.saasforge.sdk.provisioning.TenantDataCleaner;
import io.saasforge.sdk.provisioning.TenantDeleted;
import io.saasforge.sdk.web.PageQuery;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以业务键维护共享待办状态，并按数据库中的当前资格返回处理入口。 */
@Service
public class TodoService implements TenantDataCleaner {
    private final TodoMapper todos;
    private final MembershipService memberships;

    private final RoleService roles;
    private final PlanService plans;

    TodoService(TodoMapper todos, MembershipService memberships, RoleService roles, PlanService plans) {
        this.todos = todos;
        this.memberships = memberships;
        this.roles = roles;
        this.plans = plans;
    }

    @Transactional
    public void open(TodoOpened event) {
        UUID id = todos.open(
                UuidV7.generate(),
                event,
                event.occurredAt().truncatedTo(ChronoUnit.SECONDS),
                event.occurredAt().getNano());
        if (id == null) {
            return;
        }
        todos.clearMemberships(event.tenantId(), id);
        List<UUID> ids = event.target().membershipIds().stream()
                .distinct()
                .filter(member -> memberships.exists(event.tenantId(), member))
                .toList();
        if (!ids.isEmpty()) {
            todos.addMemberships(event.tenantId(), id, ids);
        }
    }

    @Transactional
    public void close(TodoClosed event) {
        todos.close(
                UuidV7.generate(),
                event,
                event.occurredAt().truncatedTo(ChronoUnit.SECONDS),
                event.occurredAt().getNano());
    }

    @Override
    @Transactional
    public void clean(TenantDeleted event) {
        todos.clearTenantMemberships(event.tenantId());
        todos.markTenantDeleted(event.tenantId());
    }

    @Transactional(readOnly = true)
    public TodoPage page(TenantActor actor, PageQuery query) {
        if (!memberships
                .status(actor.tenantId(), actor.membershipId())
                .filter(MembershipStatus.ACTIVE::equals)
                .isPresent()) {
            throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
        }
        roles.requirePermission(actor.membershipId(), IamPermissions.TODO_READ);
        // Token 与 Redis 的旧版本快照仍可能存在；待办资格须与本次请求时的角色、Plan 一致。
        var visibility = new TodoVisibility(
                actor.tenantId(),
                actor.membershipId(),
                roles.grantsOf(actor.membershipId()).permissions(),
                plans.subAppsOf(actor.tenantId()).orElse(null));
        return new TodoPage(
                todos.page(visibility, query.offset(), query.size()).stream()
                        .map(TodoResponse::from)
                        .toList(),
                todos.count(visibility));
    }
}
