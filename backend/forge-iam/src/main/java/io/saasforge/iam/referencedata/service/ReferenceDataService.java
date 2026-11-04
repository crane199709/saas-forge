package io.saasforge.iam.referencedata.service;

import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.permission.model.ServiceDictionary;
import io.saasforge.iam.permission.model.ServiceParameter;
import io.saasforge.iam.permission.service.IamPermissions;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.referencedata.mapper.DictionaryItemMapper;
import io.saasforge.iam.referencedata.mapper.ParameterOverrideMapper;
import io.saasforge.iam.referencedata.model.DictionaryItemRow;
import io.saasforge.iam.referencedata.model.ParameterOverrideRow;
import io.saasforge.iam.referencedata.model.TenantDictionary;
import io.saasforge.iam.referencedata.model.TenantDictionaryItem;
import io.saasforge.iam.referencedata.model.TenantParameter;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.Dictionary;
import io.saasforge.sdk.core.ResourceManifest.DictionaryItem;
import io.saasforge.sdk.core.ResourceManifest.Parameter;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.mq.EventPublisher;
import io.saasforge.sdk.provisioning.ReferenceDataChanged;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 租户的参考数据：服务在 Resource Manifest 中声明的 Dictionary 与 Parameter，合并租户对 Extensible Dictionary 的修改与 Parameter 的覆盖值。
 *
 * <p>默认值只来自清单，租户只在修改或自定义时才有数据，开通租户无需初始化。每次修改都在同一事务中经 Outbox 发布
 * {@link ReferenceDataChanged}，业务服务据此清除该租户的缓存。
 */
@Service
public class ReferenceDataService {
    private static final Comparator<TenantDictionaryItem> ITEM_ORDER = Comparator.comparing(
                    (TenantDictionaryItem item) -> TenantDictionaryItem.RETIRED.equals(item.status()))
            .thenComparingInt(TenantDictionaryItem::sort)
            .thenComparing(TenantDictionaryItem::code);

    private final DictionaryItemMapper items;
    private final ParameterOverrideMapper overrides;
    private final PermissionService permissions;
    private final RoleService roles;
    private final AuditRecorder audits;
    private final EventPublisher events;
    private final TransactionTemplate transactions;
    private final JsonMapper json;

    ReferenceDataService(
            DictionaryItemMapper items,
            ParameterOverrideMapper overrides,
            PermissionService permissions,
            RoleService roles,
            AuditRecorder audits,
            EventPublisher events,
            TransactionTemplate transactions,
            JsonMapper json) {
        this.items = items;
        this.overrides = overrides;
        this.permissions = permissions;
        this.roles = roles;
        this.audits = audits;
        this.events = events;
        this.transactions = transactions;
        this.json = json;
    }

    /**
     * 租户的全部字典，含停用与下线的项，不校验权限：字典对租户全部成员开放，供前端翻译名称；业务服务按此判断可选项。
     * 只返回各服务当前清单中仍声明的字典，按服务名、声明顺序排列。
     */
    public List<TenantDictionary> dictionaries(UUID tenantId) {
        Map<String, List<DictionaryItemRow>> settings = new HashMap<>();
        for (DictionaryItemRow row : items.list(tenantId)) {
            settings.computeIfAbsent(key(row.service(), row.dictionary()), key -> new ArrayList<>())
                    .add(row);
        }
        // 旧版本按从新到旧排列，同一编码以最后一次声明的名称与位置为准。
        Map<String, Map<String, PastItem>> past = new HashMap<>();
        for (ServiceDictionary previous : permissions.previousDictionaries()) {
            Map<String, PastItem> known = past.computeIfAbsent(
                    key(previous.service(), previous.dictionary().code()), key -> new LinkedHashMap<>());
            List<DictionaryItem> declared = previous.dictionary().items();
            for (int i = 0; i < declared.size(); i++) {
                known.putIfAbsent(
                        declared.get(i).code(), new PastItem(declared.get(i).name(), i + 1));
            }
        }
        return permissions.currentDictionaries().stream()
                .map(current -> {
                    String key = key(current.service(), current.dictionary().code());
                    return merge(
                            current.service(),
                            current.dictionary(),
                            settings.getOrDefault(key, List.of()),
                            past.getOrDefault(key, Map.of()));
                })
                .toList();
    }

    /**
     * 租户的全部参数及生效值，不校验权限，供业务服务读取。覆盖值只在参数仍可修改且仍满足声明时生效，否则为默认值：
     * 服务的新版本可能收紧约束、取消可修改或删除参数，覆盖值保留但不再生效。按服务名、声明顺序排列。
     */
    public List<TenantParameter> parameters(UUID tenantId) {
        Map<String, ParameterOverrideRow> values = new HashMap<>();
        for (ParameterOverrideRow row : overrides.list(tenantId)) values.put(key(row.service(), row.code()), row);
        List<TenantDictionary> dictionaries = dictionaries(tenantId);
        return permissions.currentParameters().stream()
                .map(current -> {
                    ParameterOverrideRow row = values.get(
                            key(current.service(), current.parameter().code()));
                    Object value = row == null ? null : json.readValue(row.value(), Object.class);
                    boolean effective = row != null
                            && current.parameter().tenantEditable()
                            && accepts(dictionaries, current, value);
                    return view(current, effective ? value : current.parameter().defaultValue(), effective);
                })
                .toList();
    }

    /** 管理页可修改的参数及当前值。 */
    public List<TenantParameter> editableParameters(TenantActor actor) {
        requireManager(actor);
        return parameters(actor.tenantId()).stream()
                .filter(TenantParameter::tenantEditable)
                .toList();
    }

    /**
     * 在 Extensible Dictionary 中新增自定义项，编码加 {@value ResourceManifest#CUSTOM_ITEM_PREFIX} 前缀，排在现有项之后。
     *
     * @param code 租户填写的编码，不含前缀
     */
    public TenantDictionaryItem createItem(
            TenantActor actor, String service, String dictionary, String code, String name) {
        requireManager(actor);
        String full = ResourceManifest.CUSTOM_ITEM_PREFIX + code;
        return transactions.execute(status -> {
            TenantDictionary current = lockExtensible(actor, service, dictionary);
            requireNameFree(current, name, null);
            int sort = current.items().stream()
                            .mapToInt(TenantDictionaryItem::sort)
                            .max()
                            .orElse(0)
                    + 1;
            if (items.insertCustom(
                            actor.tenantId(),
                            UuidV7.generate(),
                            service,
                            dictionary,
                            full,
                            name,
                            sort,
                            actor.identityId())
                    == 0) {
                throw new ForgeException(ReferenceDataErrorCode.ITEM_CODE_TAKEN, full);
            }
            changed(
                    actor,
                    itemAudit(actor, "dictionary.itemCreate", service, dictionary, full)
                            .detail("name", name));
            return new TenantDictionaryItem(full, name, sort, true, TenantDictionaryItem.ENABLED);
        });
    }

    /** 修改字典项的显示名与排序；编码不能改。可选项改名时不能与其他可选项重名。 */
    public TenantDictionaryItem updateItem(
            TenantActor actor, String service, String dictionary, String code, String name, int sort) {
        requireManager(actor);
        return transactions.execute(status -> {
            TenantDictionary current = lockExtensible(actor, service, dictionary);
            TenantDictionaryItem item = requireItem(current, code);
            if (item.selectable()) requireNameFree(current, name, code);
            items.saveSettings(
                    actor.tenantId(),
                    UuidV7.generate(),
                    service,
                    dictionary,
                    code,
                    item.custom(),
                    name,
                    sort,
                    actor.identityId());
            changed(
                    actor,
                    itemAudit(actor, "dictionary.itemUpdate", service, dictionary, code)
                            .detail("name", name)
                            .detail("sort", sort));
            return new TenantDictionaryItem(code, name, sort, item.custom(), item.status());
        });
    }

    /** 停用字典项：不再可选，字典接口仍返回并带标记。自定义项只能停用，不提供删除。 */
    public TenantDictionaryItem disableItem(TenantActor actor, String service, String dictionary, String code) {
        return setEnabled(actor, service, dictionary, code, false);
    }

    /** 启用已停用的字典项；与现有可选项重名时拒绝。 */
    public TenantDictionaryItem enableItem(TenantActor actor, String service, String dictionary, String code) {
        return setEnabled(actor, service, dictionary, code, true);
    }

    private TenantDictionaryItem setEnabled(
            TenantActor actor, String service, String dictionary, String code, boolean enabled) {
        requireManager(actor);
        return transactions.execute(status -> {
            TenantDictionary current = lockExtensible(actor, service, dictionary);
            TenantDictionaryItem item = requireItem(current, code);
            if (enabled) requireNameFree(current, item.name(), code);
            // 内置项首次修改时新建设置行，名称与排序留空沿用声明；自定义项总有行，传入的当前值不会被写入。
            items.saveEnabled(
                    actor.tenantId(),
                    UuidV7.generate(),
                    service,
                    dictionary,
                    code,
                    item.custom(),
                    item.custom() ? item.name() : null,
                    item.custom() ? item.sort() : null,
                    enabled,
                    actor.identityId());
            String action = enabled ? "dictionary.itemEnable" : "dictionary.itemDisable";
            changed(actor, itemAudit(actor, action, service, dictionary, code));
            return new TenantDictionaryItem(
                    code,
                    item.name(),
                    item.sort(),
                    item.custom(),
                    enabled ? TenantDictionaryItem.ENABLED : TenantDictionaryItem.DISABLED);
        });
    }

    /**
     * 覆盖可修改参数的值，值须满足声明的类型与约束；{@code dictionary} 类型须为该字典在本租户的可选项。
     *
     * @param value 覆盖值，JSON 写法同默认值
     */
    public TenantParameter overrideParameter(TenantActor actor, String service, String code, Object value) {
        requireManager(actor);
        ServiceParameter parameter = requireEditable(service, code);
        if (!accepts(dictionaries(actor.tenantId()), parameter, value)) {
            throw new ForgeException(ReferenceDataErrorCode.INVALID_PARAMETER_VALUE, code);
        }
        transactions.executeWithoutResult(status -> {
            overrides.save(
                    actor.tenantId(),
                    UuidV7.generate(),
                    service,
                    code,
                    json.writeValueAsString(value),
                    actor.identityId());
            changed(
                    actor,
                    parameterAudit(actor, "parameter.override", service, code).detail("value", value));
        });
        return view(parameter, value, true);
    }

    /** 恢复默认值；没有覆盖值时不做修改，也不发事件。 */
    public void resetParameter(TenantActor actor, String service, String code) {
        requireManager(actor);
        requireEditable(service, code);
        transactions.executeWithoutResult(status -> {
            if (overrides.delete(actor.tenantId(), service, code, actor.identityId()) > 0) {
                changed(actor, parameterAudit(actor, "parameter.reset", service, code));
            }
        });
    }

    private void requireManager(TenantActor actor) {
        roles.requirePermission(actor.membershipId(), IamPermissions.REFERENCE_DATA_MANAGE);
    }

    // 加锁后再读取合并结果，名称唯一检查与写入之间不会插入同一字典的其他修改。
    private TenantDictionary lockExtensible(TenantActor actor, String service, String dictionary) {
        items.lockDictionary(actor.tenantId(), service, dictionary);
        TenantDictionary current = dictionaries(actor.tenantId()).stream()
                .filter(candidate ->
                        candidate.service().equals(service) && candidate.code().equals(dictionary))
                .findFirst()
                .orElseThrow(() -> new ForgeException(ReferenceDataErrorCode.DICTIONARY_NOT_FOUND));
        if (!current.extensible()) throw new ForgeException(ReferenceDataErrorCode.DICTIONARY_READ_ONLY);
        return current;
    }

    private static TenantDictionaryItem requireItem(TenantDictionary dictionary, String code) {
        return dictionary.items().stream()
                .filter(item -> item.code().equals(code) && !TenantDictionaryItem.RETIRED.equals(item.status()))
                .findFirst()
                .orElseThrow(() -> new ForgeException(ReferenceDataErrorCode.ITEM_NOT_FOUND));
    }

    // 导入按名称匹配字典项，可选项之间名称必须唯一；停用与下线的项不参与。
    private static void requireNameFree(TenantDictionary dictionary, String name, String except) {
        boolean taken = dictionary.items().stream()
                .anyMatch(item -> item.selectable()
                        && !item.code().equals(except)
                        && item.name().equals(name));
        if (taken) throw new ForgeException(ReferenceDataErrorCode.ITEM_NAME_TAKEN, name);
    }

    private ServiceParameter requireEditable(String service, String code) {
        ServiceParameter parameter = permissions.currentParameters().stream()
                .filter(candidate -> candidate.service().equals(service)
                        && candidate.parameter().code().equals(code))
                .findFirst()
                .orElseThrow(() -> new ForgeException(ReferenceDataErrorCode.PARAMETER_NOT_FOUND));
        if (!parameter.parameter().tenantEditable()) {
            throw new ForgeException(ReferenceDataErrorCode.PARAMETER_NOT_EDITABLE);
        }
        return parameter;
    }

    private static boolean accepts(List<TenantDictionary> dictionaries, ServiceParameter current, Object value) {
        Parameter parameter = current.parameter();
        try {
            parameter.requireValue(value);
        } catch (IllegalArgumentException error) {
            return false;
        }
        if (!"dictionary".equals(parameter.type())) return true;
        return dictionaries.stream()
                .filter(dictionary -> dictionary.service().equals(current.service())
                        && dictionary.code().equals(parameter.dictionary()))
                .flatMap(dictionary -> dictionary.items().stream())
                .anyMatch(item -> item.selectable() && item.code().equals(value));
    }

    private void changed(TenantActor actor, AuditEvent.Builder audit) {
        // iam 的请求不绑定租户上下文，事件须显式带上租户，订阅方才能按租户清除缓存。
        CurrentTenant.run(
                actor.tenantId(),
                () -> events.publish(ReferenceDataChanged.TOPIC, new ReferenceDataChanged(actor.tenantId())));
        audits.record(audit.build());
    }

    private static AuditEvent.Builder itemAudit(
            TenantActor actor, String action, String service, String dictionary, String code) {
        return audit(actor, action, "dictionaryItem", service + ":" + dictionary + ":" + code);
    }

    private static AuditEvent.Builder parameterAudit(TenantActor actor, String action, String service, String code) {
        return audit(actor, action, "parameter", service + ":" + code);
    }

    private static AuditEvent.Builder audit(TenantActor actor, String action, String type, String id) {
        return AuditEvent.builder(AuditRealm.TENANT, AuditCategory.OPERATION, action)
                .tenant(actor.tenantId())
                .actor(actor.identityId())
                .resource(type, id);
    }

    /**
     * 合并一个字典。可扩展字典：内置项套用租户设置，自定义项来自租户；固定字典忽略租户设置，已有的自定义项（字典改为固定前创建）下线。
     * 旧版本声明而当前版本删除的内置项都作为下线项返回。
     */
    private static TenantDictionary merge(
            String service, Dictionary dictionary, List<DictionaryItemRow> rows, Map<String, PastItem> past) {
        boolean extensible = dictionary.extensible();
        Map<String, DictionaryItemRow> byCode = new HashMap<>();
        for (DictionaryItemRow row : rows) byCode.put(row.code(), row);
        List<TenantDictionaryItem> merged = new ArrayList<>();
        Map<String, Boolean> declared = new HashMap<>();
        List<DictionaryItem> builtIns = dictionary.items();
        for (int i = 0; i < builtIns.size(); i++) {
            DictionaryItem item = builtIns.get(i);
            declared.put(item.code(), true);
            DictionaryItemRow row = extensible ? byCode.get(item.code()) : null;
            merged.add(new TenantDictionaryItem(
                    item.code(),
                    row == null || row.name() == null ? item.name() : row.name(),
                    row == null || row.sortOrder() == null ? i + 1 : row.sortOrder(),
                    false,
                    row == null || row.enabled() ? TenantDictionaryItem.ENABLED : TenantDictionaryItem.DISABLED));
        }
        past.forEach((code, item) -> {
            if (declared.containsKey(code)) return;
            DictionaryItemRow row = byCode.get(code);
            merged.add(new TenantDictionaryItem(
                    code,
                    row == null || row.name() == null ? item.name() : row.name(),
                    item.sort(),
                    false,
                    TenantDictionaryItem.RETIRED));
        });
        for (DictionaryItemRow row : rows) {
            if (!row.custom()) continue;
            String status = !extensible
                    ? TenantDictionaryItem.RETIRED
                    : row.enabled() ? TenantDictionaryItem.ENABLED : TenantDictionaryItem.DISABLED;
            merged.add(new TenantDictionaryItem(row.code(), row.name(), row.sortOrder(), true, status));
        }
        merged.sort(ITEM_ORDER);
        return new TenantDictionary(service, dictionary.code(), dictionary.name(), extensible, merged);
    }

    private static TenantParameter view(ServiceParameter current, Object value, boolean overridden) {
        Parameter parameter = current.parameter();
        return new TenantParameter(
                current.service(),
                parameter.code(),
                parameter.name(),
                parameter.type(),
                parameter.dictionary(),
                parameter.defaultValue(),
                parameter.min(),
                parameter.max(),
                parameter.maxLength(),
                parameter.tenantEditable(),
                value,
                overridden);
    }

    // 服务名与编码都不含冒号（工单 01），拼接后不会混淆。
    private static String key(String service, String code) {
        return service + ":" + code;
    }

    private record PastItem(String name, int sort) {}
}
