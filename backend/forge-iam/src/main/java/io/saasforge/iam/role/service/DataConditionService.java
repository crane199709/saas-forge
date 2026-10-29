package io.saasforge.iam.role.service;

import io.saasforge.iam.membership.service.MembershipService;
import io.saasforge.iam.membership.service.SubjectAttributeSource;
import io.saasforge.iam.organization.model.DepartmentRow;
import io.saasforge.iam.organization.model.ExtendedAttributeRow;
import io.saasforge.iam.organization.service.DepartmentService;
import io.saasforge.iam.organization.service.ExtendedAttributeService;
import io.saasforge.iam.organization.service.PositionService;
import io.saasforge.iam.permission.model.ServiceDataResource;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.role.model.ConditionAttribute;
import io.saasforge.iam.role.model.ConditionOptions;
import io.saasforge.iam.role.model.DataPolicy;
import io.saasforge.iam.role.model.DataResourceOption;
import io.saasforge.iam.role.model.PolicyPreview;
import io.saasforge.iam.role.model.ValueOption;
import io.saasforge.sdk.abac.AttributeDefinition;
import io.saasforge.sdk.abac.DataResourceDefinition;
import io.saasforge.sdk.abac.DataScope;
import io.saasforge.sdk.abac.Expression;
import io.saasforge.sdk.abac.PolicyEvaluator;
import io.saasforge.sdk.abac.PolicyValidationException;
import io.saasforge.sdk.core.ResourceManifest.DataAttribute;
import io.saasforge.sdk.core.api.ForgeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 角色上 Data Policy 的属性目录、校验与预览。校验与业务服务的 SDK 使用同一份属性目录：清单声明的资源属性、平台按组织模型提供的
 * 主体属性、租户的 Extended Attribute 与环境属性，保存时通过的条件在业务服务中一定能加载。权限由调用方校验。
 */
@Service
public class DataConditionService {
    /** 自定义条件 JSON 的长度上限：条件随权限明细发布到 Redis，并在每次查询时编译为 SQL。 */
    static final int MAX_CONDITION_LENGTH = 8_192;

    /** 一次预览的样例数据条数上限。 */
    static final int MAX_SAMPLES = 50;

    private final PermissionService permissions;
    private final ExtendedAttributeService extendedAttributes;
    private final DepartmentService departments;
    private final PositionService positions;
    private final MembershipService memberships;
    private final SubjectAttributeSource subjects;

    DataConditionService(
            PermissionService permissions,
            ExtendedAttributeService extendedAttributes,
            DepartmentService departments,
            PositionService positions,
            MembershipService memberships,
            SubjectAttributeSource subjects) {
        this.permissions = permissions;
        this.extendedAttributes = extendedAttributes;
        this.departments = departments;
        this.positions = positions;
        this.memberships = memberships;
        this.subjects = subjects;
    }

    /** 各业务服务当前声明的 Data Resource，带可用的预设与可引用的资源属性。 */
    public List<DataResourceOption> dataResources() {
        return permissions.currentDataResources().stream()
                .map(current -> new DataResourceOption(
                        current.resource().code(),
                        current.resource().name(),
                        current.service(),
                        DataResourceDefinition.of(current.resource()).availableScopes().stream()
                                .map(RoleService::scopeName)
                                .toList(),
                        resourceAttributes(current)))
                .toList();
    }

    /** 条件编辑器中可引用的主体与环境属性，以及部门、岗位取值。 */
    public ConditionOptions options(UUID tenantId) {
        var subject = new ArrayList<ConditionAttribute>();
        for (AttributeDefinition attribute : DataResourceDefinition.SUBJECT_ATTRIBUTES) {
            subject.add(attribute(attribute, null));
        }
        for (ExtendedAttributeRow definition : extendedAttributes.definitions(tenantId)) {
            subject.add(new ConditionAttribute(
                    "subject.attributes." + definition.code(), definition.name(), definition.type(), false));
        }
        return new ConditionOptions(
                subject,
                DataResourceDefinition.ENVIRONMENT_ATTRIBUTES.stream()
                        .map(attribute -> attribute(attribute, null))
                        .toList(),
                departmentOptions(departments.all(tenantId)),
                positions.all(tenantId).stream()
                        .map(position -> new ValueOption(position.id().toString(), position.name()))
                        .toList());
    }

    /**
     * 校验策略并按资源编码排序：资源须在当前清单中声明且不重复；预设须是该资源可用的预设；自定义条件须能按本租户的属性目录加载。
     *
     * @throws ForgeException 400 {@code role.invalidDataPolicy} 或 {@code role.invalidDataCondition}
     */
    public List<DataPolicy> requireValid(UUID tenantId, Collection<DataPolicy> policies) {
        Map<String, DataResourceDefinition> definitions = currentDefinitions();
        Map<String, String> types = extendedTypes(tenantId);
        Map<String, DataPolicy> byResource = new TreeMap<>();
        for (DataPolicy policy : policies) {
            DataResourceDefinition definition = definitions.get(policy.resource());
            if (definition == null || byResource.containsKey(policy.resource())) {
                throw new ForgeException(RoleErrorCode.INVALID_DATA_POLICY, policy.resource());
            }
            parse(definition, types, policy);
            byResource.put(
                    policy.resource(),
                    DataPolicy.CUSTOM.equals(policy.scope())
                            ? policy
                            : DataPolicy.preset(policy.resource(), RoleService.scopeName(scopeOf(policy))));
        }
        return List.copyOf(byResource.values());
    }

    /**
     * 以指定成员当前的主体属性与此刻的环境属性，对样例数据逐条求值策略。与业务服务的单对象校验使用同一个求值器。
     *
     * @param samples 每条为资源属性编码到值，未给出的属性视为 null
     * @throws ForgeException 策略不合法、成员不存在或样例数据不合法
     */
    public PolicyPreview preview(
            UUID tenantId, DataPolicy policy, UUID membershipId, List<Map<String, Object>> samples) {
        DataResourceDefinition definition = currentDefinitions().get(policy.resource());
        if (definition == null) throw new ForgeException(RoleErrorCode.INVALID_DATA_POLICY, policy.resource());
        Expression expression = parse(definition, extendedTypes(tenantId), policy);
        if (!memberships.exists(tenantId, membershipId)) throw new ForgeException(RoleErrorCode.MEMBERSHIP_NOT_FOUND);
        if (samples.size() > MAX_SAMPLES) {
            throw ForgeException.withErrorMessage(
                    RoleErrorCode.INVALID_PREVIEW_SAMPLE,
                    "At most " + MAX_SAMPLES + " samples",
                    "最多 " + MAX_SAMPLES + " 条");
        }
        Map<String, Object> context = subjects.subjectOf(tenantId, membershipId).toContext(membershipId);
        context.putAll(DataResourceDefinition.environment(Instant.now()));
        var declared = definition.resourceAttributes().stream()
                .map(attribute -> attribute.name().substring("resource.".length()))
                .toList();
        var matches = new ArrayList<Boolean>(samples.size());
        for (int i = 0; i < samples.size(); i++) {
            Map<String, Object> sample = samples.get(i);
            for (String code : sample.keySet()) {
                if (!declared.contains(code)) {
                    throw ForgeException.withErrorMessage(
                            RoleErrorCode.INVALID_PREVIEW_SAMPLE,
                            "Sample " + i + " has undeclared attribute " + code,
                            "第 " + (i + 1) + " 条含未声明的属性 " + code);
                }
            }
            try {
                matches.add(PolicyEvaluator.isSatisfied(expression, context, definition.resourceValues(sample)));
            } catch (IllegalArgumentException invalid) {
                throw ForgeException.withErrorMessage(
                        RoleErrorCode.INVALID_PREVIEW_SAMPLE,
                        "Sample " + i + " has an invalid value",
                        "第 " + (i + 1) + " 条：" + invalid.getMessage());
            }
        }
        return new PolicyPreview(new TreeMap<>(context), matches);
    }

    private static Expression parse(DataResourceDefinition definition, Map<String, String> types, DataPolicy policy) {
        if (!DataPolicy.CUSTOM.equals(policy.scope())) {
            DataScope scope = scopeOf(policy);
            if (policy.condition() != null || !definition.availableScopes().contains(scope)) {
                throw new ForgeException(RoleErrorCode.INVALID_DATA_POLICY, policy.resource());
            }
            return definition.parse(scope.template());
        }
        if (policy.condition() == null) {
            throw invalidCondition(policy, "Missing condition", "缺少条件");
        }
        if (policy.condition().length() > MAX_CONDITION_LENGTH) {
            throw invalidCondition(policy, "Condition too long", "条件过长，请拆分或简化");
        }
        try {
            Expression expression = definition.parse(policy.condition(), types);
            // 空的「全部满足」恒为真：编辑器删光条件后会放开全部数据，空的「任一满足」恒为假也没有意义，一律拒绝
            if (hasEmptyGroup(expression)) throw invalidCondition(policy, "Empty condition group", "条件组不能为空");
            return expression;
        } catch (PolicyValidationException invalid) {
            throw invalidCondition(policy, "Invalid condition at " + invalid.path(), invalid.getMessage());
        }
    }

    private static boolean hasEmptyGroup(Expression expression) {
        if (expression instanceof Expression.Not not) return hasEmptyGroup(not.child());
        List<Expression> children;
        if (expression instanceof Expression.And and) children = and.children();
        else if (expression instanceof Expression.Or or) children = or.children();
        else return false;
        return children.isEmpty() || children.stream().anyMatch(DataConditionService::hasEmptyGroup);
    }

    private static ForgeException invalidCondition(DataPolicy policy, String errorMessage, String reason) {
        return ForgeException.withErrorMessage(
                RoleErrorCode.INVALID_DATA_CONDITION,
                "Data Resource " + policy.resource() + ": " + errorMessage,
                policy.resource(),
                reason);
    }

    private static DataScope scopeOf(DataPolicy policy) {
        try {
            if (policy.scope() != null) return RoleService.scopeOf(policy.scope());
        } catch (IllegalArgumentException unknown) {
            // 按非法策略处理
        }
        throw new ForgeException(RoleErrorCode.INVALID_DATA_POLICY, policy.resource());
    }

    private Map<String, DataResourceDefinition> currentDefinitions() {
        Map<String, DataResourceDefinition> definitions = new HashMap<>();
        for (ServiceDataResource current : permissions.currentDataResources()) {
            definitions.put(current.resource().code(), DataResourceDefinition.of(current.resource()));
        }
        return definitions;
    }

    private Map<String, String> extendedTypes(UUID tenantId) {
        Map<String, String> types = new HashMap<>();
        for (ExtendedAttributeRow definition : extendedAttributes.definitions(tenantId)) {
            types.put(definition.code(), definition.type());
        }
        return types;
    }

    private static List<ConditionAttribute> resourceAttributes(ServiceDataResource current) {
        Map<String, String> labels = new HashMap<>();
        for (DataAttribute attribute : current.resource().attributes()) {
            labels.put("resource." + attribute.code(), attribute.name());
        }
        return DataResourceDefinition.of(current.resource()).resourceAttributes().stream()
                .map(attribute -> attribute(attribute, labels.get(attribute.name())))
                .toList();
    }

    private static ConditionAttribute attribute(AttributeDefinition attribute, String label) {
        return new ConditionAttribute(
                attribute.name(), label, attribute.type().name().toLowerCase(Locale.ROOT), attribute.multiValued());
    }

    // 部门按路径排序，上级总在下级之前；上级已删除的部门以自身名称开头。
    private static List<ValueOption> departmentOptions(List<DepartmentRow> rows) {
        Map<UUID, String> labels = new HashMap<>();
        return rows.stream()
                .map(row -> {
                    String parent = row.parentId() == null ? null : labels.get(row.parentId());
                    String label = parent == null ? row.name() : parent + " / " + row.name();
                    labels.put(row.id(), label);
                    return new ValueOption(row.path(), label);
                })
                .toList();
    }
}
