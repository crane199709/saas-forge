package io.saasforge.iam.organization.service;

import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.membership.service.MembershipService;
import io.saasforge.iam.organization.mapper.DepartmentMapper;
import io.saasforge.iam.organization.mapper.ExtendedAttributeMapper;
import io.saasforge.iam.organization.model.ExtendedAttributeRow;
import io.saasforge.iam.permission.service.IamPermissions;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 租户为 Membership 定义的 Extended Attribute；值以 JSONB 存于 Membership，作为主体属性发布。 */
@Service
public class ExtendedAttributeService {
    /** 字符串值的长度上限：值随权限明细发布到 Redis，并会进入数据权限的 SQL 参数。 */
    static final int MAX_STRING_LENGTH = 500;

    private final ExtendedAttributeMapper attributes;
    private final DepartmentMapper departments;
    private final MembershipService memberships;
    private final RoleService roles;
    private final SubjectChangePublisher subjects;
    private final TransactionTemplate transactions;

    ExtendedAttributeService(
            ExtendedAttributeMapper attributes,
            DepartmentMapper departments,
            MembershipService memberships,
            RoleService roles,
            SubjectChangePublisher subjects,
            TransactionTemplate transactions) {
        this.attributes = attributes;
        this.departments = departments;
        this.memberships = memberships;
        this.roles = roles;
        this.subjects = subjects;
        this.transactions = transactions;
    }

    public List<ExtendedAttributeRow> list(TenantActor actor) {
        roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
        return attributes.list(actor.tenantId());
    }

    /** 租户的全部定义，不校验权限；供角色编辑中的条件编辑器列出可用的主体属性。 */
    public List<ExtendedAttributeRow> definitions(UUID tenantId) {
        return attributes.list(tenantId);
    }

    public ExtendedAttributeRow create(TenantActor actor, String code, String name, String type, boolean required) {
        roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
        UUID id = UuidV7.generate();
        // 与成员分配同锁：否则并发保存的分配可能在新的必填属性生效前完成校验。
        transactions.executeWithoutResult(status -> {
            departments.lockTenant(actor.tenantId());
            if (attributes.insert(actor.tenantId(), id, code, name, type, required, actor.identityId()) == 0) {
                throw new ForgeException(OrganizationErrorCode.ATTRIBUTE_CODE_TAKEN, code);
            }
        });
        return new ExtendedAttributeRow(id, code, name, type, required);
    }

    /**
     * 只能改名称与是否必填。改为必填不回溯校验已有成员，成员下一次保存分配时才要求填写：
     * 一次性补齐全部成员不现实，而缺失的值在数据权限中按缺失处理，不会放宽可见范围以外的数据。
     */
    public ExtendedAttributeRow update(TenantActor actor, UUID id, String name, boolean required) {
        roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
        if (attributes.update(actor.tenantId(), id, name, required, actor.identityId()) == 0) {
            throw new ForgeException(OrganizationErrorCode.ATTRIBUTE_NOT_FOUND);
        }
        return attributes.find(actor.tenantId(), id);
    }

    /** 删除定义并移除各成员的该属性值；之后以同一编码新建的属性不会继承旧值。 */
    public void delete(TenantActor actor, UUID id) {
        roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
        List<UUID> affected = transactions.execute(status -> {
            departments.lockTenant(actor.tenantId());
            ExtendedAttributeRow attribute = attributes.find(actor.tenantId(), id);
            if (attribute == null) throw new ForgeException(OrganizationErrorCode.ATTRIBUTE_NOT_FOUND);
            attributes.delete(actor.tenantId(), id, actor.identityId());
            return memberships.removeAttribute(actor.tenantId(), attribute.code(), actor.identityId());
        });
        subjects.changed(affected);
    }

    /**
     * 按租户的属性定义校验一个成员的全部属性值：编码必须已定义、类型一致、必填项不能缺失。值为 null 视为未填写。
     *
     * @return 去掉未填写项后的值
     */
    Map<String, Object> validate(UUID tenantId, Map<String, Object> values) {
        Map<String, ExtendedAttributeRow> definitions = new HashMap<>();
        for (ExtendedAttributeRow definition : attributes.list(tenantId))
            definitions.put(definition.code(), definition);
        Map<String, Object> result = new HashMap<>();
        values.forEach((code, value) -> {
            ExtendedAttributeRow definition = definitions.get(code);
            if (definition == null) throw invalid(code);
            if (value == null) return;
            if (!matches(definition.type(), value)) throw invalid(code);
            result.put(code, value);
        });
        for (ExtendedAttributeRow definition : definitions.values()) {
            if (definition.required() && !result.containsKey(definition.code())) throw invalid(definition.code());
        }
        return result;
    }

    private static boolean matches(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String text && text.length() <= MAX_STRING_LENGTH;
            case "number" -> value instanceof Number number && Double.isFinite(number.doubleValue());
            case "boolean" -> value instanceof Boolean;
            default -> false;
        };
    }

    private static ForgeException invalid(String code) {
        return new ForgeException(OrganizationErrorCode.INVALID_ATTRIBUTE_VALUE, code);
    }
}
