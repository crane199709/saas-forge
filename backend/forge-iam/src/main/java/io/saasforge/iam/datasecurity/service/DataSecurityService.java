package io.saasforge.iam.datasecurity.service;

import io.saasforge.iam.datasecurity.mapper.DataSecurityMapper;
import io.saasforge.iam.datasecurity.model.FieldOverrideRow;
import io.saasforge.iam.datasecurity.model.FieldResponse;
import io.saasforge.iam.datasecurity.model.GradeResponse;
import io.saasforge.iam.datasecurity.model.RuleResponse;
import io.saasforge.iam.datasecurity.model.RuleRow;
import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.permission.service.IamPermissions;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.permission.service.PlatformPermissions;
import io.saasforge.iam.platformrole.service.PlatformRoleService;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.iam.tenant.service.TenantService;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.provisioning.TenantDataCleaner;
import io.saasforge.sdk.provisioning.TenantDeleted;
import io.saasforge.sdk.security.EffectiveFieldPolicy;
import io.saasforge.sdk.security.MaskingEngine;
import io.saasforge.sdk.security.MaskingPreview;
import io.saasforge.sdk.security.MaskingRule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 合并字段登记、固定分级与租户规则选择；只经 PermissionService 读取当前清单。 */
@Service
public class DataSecurityService implements TenantDataCleaner {
    private final DataSecurityMapper mapper;
    private final PlatformRoleService platformRoles;
    private final RoleService roles;
    private final PermissionService permissions;
    private final TenantService tenants;
    private final AuditRecorder audits;
    private final MaskingEngine engine = new MaskingEngine();
    private final TransactionTemplate tenantReads;

    DataSecurityService(
            DataSecurityMapper mapper,
            PlatformRoleService platformRoles,
            RoleService roles,
            PermissionService permissions,
            TenantService tenants,
            AuditRecorder audits,
            PlatformTransactionManager transactions) {
        this.mapper = mapper;
        this.platformRoles = platformRoles;
        this.roles = roles;
        this.permissions = permissions;
        this.tenants = tenants;
        this.audits = audits;
        this.tenantReads = new TransactionTemplate(transactions);
        this.tenantReads.setReadOnly(true);
        this.tenantReads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<GradeResponse> platformGrades(UUID actor) {
        platformRead(actor);
        return mapper.grades();
    }

    public List<RuleResponse> platformRules(UUID actor) {
        platformRead(actor);
        return rules();
    }

    public List<GradeResponse> tenantGrades(TenantActor actor) {
        tenantRead(actor);
        return mapper.grades();
    }

    public List<RuleResponse> tenantRules(TenantActor actor) {
        tenantRead(actor);
        return rules();
    }

    @Transactional(readOnly = true)
    public List<FieldResponse> fields(TenantActor actor) {
        tenantRead(actor);
        return resolve(actor.tenantId());
    }

    @Transactional
    public RuleResponse createRule(UUID actor, String code, String name, MaskingRule rule) {
        mapper.lock();
        platformManage(actor);
        validateRule(rule);
        if (mapper.rule(code) != null) throw new ForgeException(DataSecurityErrorCode.RULE_CODE_TAKEN);
        mapper.createRule(UuidV7.generate(), code, name, rule, actor);
        audit(actor, "masking-rule.create", "maskingRule", code);
        return requireRule(code).response();
    }

    @Transactional
    public RuleResponse updateRule(UUID actor, String code, String name, MaskingRule rule) {
        mapper.lock();
        platformManage(actor);
        requireRule(code);
        validateRule(rule);
        mapper.updateRule(code, name, rule, actor);
        audit(actor, "masking-rule.update", "maskingRule", code);
        return requireRule(code).response();
    }

    // 锁必须先于鉴权读取清单：MyBatis 一级缓存否则可能保留等待锁之前的 Manifest，导致引用检查漏检。
    @Transactional
    public void deleteRule(UUID actor, String code) {
        mapper.lock();
        platformManage(actor);
        requireRule(code);
        if (mapper.grades().stream().anyMatch(grade -> grade.defaultRule().equals(code))
                || permissions.currentSensitiveFields().stream()
                        .anyMatch(field -> code.equals(field.field().defaultRule()))
                || tenantOverridesUse(code)) {
            throw new ForgeException(DataSecurityErrorCode.RULE_IN_USE);
        }
        mapper.deleteRule(code, actor);
        audit(actor, "masking-rule.delete", "maskingRule", code);
    }

    @Transactional
    public GradeResponse updateGrade(UUID actor, String grade, String code) {
        mapper.lock();
        platformManage(actor);
        requireRule(code);
        if (mapper.updateGrade(grade, code, actor) != 1)
            throw new ForgeException(DataSecurityErrorCode.GRADE_NOT_FOUND);
        audit(actor, "data-grade.update", "dataGrade", grade);
        return mapper.grades().stream()
                .filter(value -> value.grade().equals(grade))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public FieldResponse override(TenantActor actor, String service, String path, String code) {
        mapper.lock();
        tenantManage(actor);
        requireField(service, path);
        requireRule(code);
        mapper.saveOverride(UuidV7.generate(), actor.tenantId(), service, path, code, actor.identityId());
        tenantAudit(actor, "field-masking.override", service, path);
        return resolve(actor.tenantId()).stream()
                .filter(field -> field.service().equals(service) && field.path().equals(path))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public void reset(TenantActor actor, String service, String path) {
        mapper.lock();
        tenantManage(actor);
        requireField(service, path);
        if (mapper.resetOverride(actor.tenantId(), service, path, actor.identityId()) > 0)
            tenantAudit(actor, "field-masking.reset", service, path);
    }

    public MaskingPreview platformPreview(UUID actor, String code, String value) {
        platformRead(actor);
        return preview(code, value);
    }

    public MaskingPreview tenantPreview(TenantActor actor, String code, String value) {
        tenantRead(actor);
        return preview(code, value);
    }

    /** 只返回已认证服务自己申报的字段；记录缺规则字段，调用通道须失败关闭。 */
    @Transactional(readOnly = true)
    public Map<String, EffectiveFieldPolicy> policies(UUID tenantId, String service) {
        Map<String, RuleRow> rules = ruleMap();
        Map<String, EffectiveFieldPolicy> result = new HashMap<>();
        for (FieldResponse field : resolve(tenantId)) {
            if (!field.service().equals(service)) continue;
            RuleRow row = rules.get(field.effectiveRule());
            if (row != null) validateRule(row.rule());
            result.put(
                    field.path(),
                    new EffectiveFieldPolicy(
                            field.path(), field.grade(), row == null ? null : row.rule(), field.exportAllowed()));
        }
        return Map.copyOf(result);
    }

    /** 启动时验证存量规则、分级默认、Manifest 默认与租户覆盖，发现破损配置拒绝启动。 */
    @Transactional
    public void validateConfiguration() {
        mapper.lock();
        mapper.rules().forEach(row -> validateRule(row.rule()));
        var grades = mapper.grades();
        if (grades.size() != 4) throw new ForgeException(DataSecurityErrorCode.INVALID_REFERENCE);
        grades.forEach(grade -> requireReference(grade.defaultRule()));
        permissions.currentSensitiveFields().forEach(field -> {
            if (field.field().defaultRule() != null)
                requireReference(field.field().defaultRule());
        });
        var currentFields = permissions.currentSensitiveFields().stream()
                .map(field -> key(field.service(), field.field().path()))
                .collect(Collectors.toSet());
        for (UUID tenant : tenants.ids()) {
            readTenantOverrides(tenant).stream()
                    .filter(row -> currentFields.contains(key(row.service(), row.path())))
                    .forEach(row -> requireReference(row.ruleCode()));
        }
    }

    @Override
    @Transactional
    public void clean(TenantDeleted event) {
        mapper.lock();
        mapper.deleteTenant(event.tenantId());
    }

    private List<FieldResponse> resolve(UUID tenantId) {
        Map<String, GradeResponse> grades =
                mapper.grades().stream().collect(Collectors.toMap(GradeResponse::grade, Function.identity()));
        Map<String, RuleRow> rules = ruleMap();
        Map<String, String> overrides = mapper.overrides(tenantId).stream()
                .collect(Collectors.toMap(row -> key(row.service(), row.path()), FieldOverrideRow::ruleCode));
        return permissions.currentSensitiveFields().stream()
                .map(current -> {
                    var field = current.field();
                    GradeResponse grade = grades.get(field.grade());
                    String override = overrides.get(key(current.service(), field.path()));
                    String effective = override != null
                            ? override
                            : field.defaultRule() != null
                                    ? field.defaultRule()
                                    : grade == null ? null : grade.defaultRule();
                    return new FieldResponse(
                            current.service(),
                            field.path(),
                            field.grade(),
                            field.defaultRule(),
                            override,
                            effective,
                            rules.containsKey(effective) ? "active" : "missing-rule",
                            grade != null && grade.exportAllowed() && !"L4".equals(field.grade()));
                })
                .toList();
    }

    private boolean tenantOverridesUse(String code) {
        var fields = permissions.currentSensitiveFields().stream()
                .map(field -> key(field.service(), field.field().path()))
                .collect(Collectors.toSet());
        for (UUID tenant : tenants.ids()) {
            if (readTenantOverrides(tenant).stream()
                    .anyMatch(row -> row.ruleCode().equals(code) && fields.contains(key(row.service(), row.path()))))
                return true;
        }
        return false;
    }

    private List<FieldOverrideRow> readTenantOverrides(UUID tenant) {
        // 外层持有全局写锁，内层在绑定租户后获取独立连接。复用外层连接会沿用无租户会话而漏掉 RLS 行。
        try (var ignored = CurrentTenant.bind(tenant)) {
            return tenantReads.execute(status -> mapper.overrides(tenant));
        }
    }

    private void requireField(String service, String path) {
        if (permissions.currentSensitiveFields().stream()
                .noneMatch(field ->
                        field.service().equals(service) && field.field().path().equals(path)))
            throw new ForgeException(DataSecurityErrorCode.FIELD_NOT_FOUND);
    }

    private List<RuleResponse> rules() {
        return mapper.rules().stream().map(RuleRow::response).toList();
    }

    private Map<String, RuleRow> ruleMap() {
        return mapper.rules().stream().collect(Collectors.toMap(RuleRow::code, Function.identity()));
    }

    private static String key(String service, String path) {
        return service + ":" + path;
    }

    private MaskingPreview preview(String code, String value) {
        RuleRow row = mapper.rule(code);
        return row == null
                ? new MaskingPreview(
                        false, null, DataSecurityErrorCode.RULE_NOT_FOUND.code(), "Masking rule is unavailable")
                : engine.preview(row.rule(), value);
    }

    private RuleRow requireRule(String code) {
        RuleRow row = mapper.rule(code);
        if (row == null) throw new ForgeException(DataSecurityErrorCode.RULE_NOT_FOUND);
        return row;
    }

    private void requireReference(String code) {
        if (mapper.rule(code) == null) throw new ForgeException(DataSecurityErrorCode.INVALID_REFERENCE);
    }

    private void validateRule(MaskingRule rule) {
        try {
            engine.validate(rule);
            if (rule.replacement() != null && rule.replacement().length() > 256)
                throw new ForgeException(DataSecurityErrorCode.INVALID_RULE);
        } catch (ForgeException error) {
            throw new ForgeException(DataSecurityErrorCode.INVALID_RULE);
        }
    }

    private void platformRead(UUID actor) {
        platformRoles.requirePermission(
                actor, PlatformPermissions.DATA_SECURITY_READ, PlatformPermissions.DATA_SECURITY_MANAGE);
    }

    private void platformManage(UUID actor) {
        platformRoles.requirePermission(actor, PlatformPermissions.DATA_SECURITY_MANAGE);
    }

    private void tenantRead(TenantActor actor) {
        var granted = roles.grantsOf(actor.membershipId()).permissions();
        if (!granted.contains(IamPermissions.DATA_SECURITY_READ)
                && !granted.contains(IamPermissions.DATA_SECURITY_MANAGE))
            throw new ForgeException(FrameworkErrorCode.AUTH_FORBIDDEN);
    }

    private void tenantManage(TenantActor actor) {
        roles.requirePermission(actor.membershipId(), IamPermissions.DATA_SECURITY_MANAGE);
    }

    private void audit(UUID actor, String action, String type, String id) {
        audits.record(AuditEvent.builder(AuditRealm.PLATFORM, AuditCategory.OPERATION, action)
                .actor(actor)
                .resource(type, id)
                .build());
    }

    private void tenantAudit(TenantActor actor, String action, String service, String path) {
        audits.record(AuditEvent.builder(AuditRealm.TENANT, AuditCategory.OPERATION, action)
                .actor(actor.identityId())
                .tenant(actor.tenantId())
                .resource("sensitiveField", key(service, path))
                .build());
    }
}
