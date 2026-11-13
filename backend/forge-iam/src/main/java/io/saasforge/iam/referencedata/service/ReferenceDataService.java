package io.saasforge.iam.referencedata.service;

import io.saasforge.iam.identity.service.IdentityService;
import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.permission.model.ServiceDictionary;
import io.saasforge.iam.permission.model.ServiceParameter;
import io.saasforge.iam.permission.service.IamPermissions;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.referencedata.mapper.DictionaryItemMapper;
import io.saasforge.iam.referencedata.mapper.ParameterOverrideMapper;
import io.saasforge.iam.referencedata.model.DictionaryHistoryDetailResponse;
import io.saasforge.iam.referencedata.model.DictionaryHistoryDiffResponse;
import io.saasforge.iam.referencedata.model.DictionaryHistoryItem;
import io.saasforge.iam.referencedata.model.DictionaryHistoryMetadataResponse;
import io.saasforge.iam.referencedata.model.DictionaryHistoryResponse;
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
import io.saasforge.sdk.core.ReferenceDataKeys;
import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.Dictionary;
import io.saasforge.sdk.core.ResourceManifest.DictionaryItem;
import io.saasforge.sdk.core.ResourceManifest.Parameter;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 租户的参考数据：服务在 Resource Manifest 中声明的 Dictionary 与 Parameter，合并租户对 Extensible Dictionary 的修改与 Parameter 的覆盖值。
 *
 * <p>默认值只来自清单，租户只在修改或自定义时才有数据，开通租户无需初始化。每次修改在事务提交后递增 Redis 中该租户的版本号
 * （{@link ReferenceDataKeys#version(UUID)}，做法同 ADR-0005 的 Membership version），业务服务读取前比对版本，不同即重新拉取。
 */
@Service
public class ReferenceDataService {
    private static final Logger log = LoggerFactory.getLogger(ReferenceDataService.class);
    private static final Comparator<TenantDictionaryItem> ITEM_ORDER = Comparator.comparing(
                    (TenantDictionaryItem item) -> TenantDictionaryItem.RETIRED.equals(item.status()))
            .thenComparingInt(TenantDictionaryItem::sort)
            .thenComparing(TenantDictionaryItem::code);

    private final DictionaryItemMapper items;
    private final DictionaryHistoryService history;
    private final IdentityService identities;
    private final ParameterOverrideMapper overrides;
    private final PermissionService permissions;
    private final RoleService roles;
    private final AuditRecorder audits;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transactions;
    private final JsonMapper json;

    ReferenceDataService(
            DictionaryItemMapper items,
            DictionaryHistoryService history,
            IdentityService identities,
            ParameterOverrideMapper overrides,
            PermissionService permissions,
            RoleService roles,
            AuditRecorder audits,
            StringRedisTemplate redis,
            TransactionTemplate transactions,
            JsonMapper json) {
        this.items = items;
        this.history = history;
        this.identities = identities;
        this.overrides = overrides;
        this.permissions = permissions;
        this.roles = roles;
        this.audits = audits;
        this.redis = redis;
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
            settings.computeIfAbsent(
                            ResourceManifest.qualifiedCode(row.service(), row.dictionary()), key -> new ArrayList<>())
                    .add(row);
        }
        // 旧版本按从新到旧排列，同一编码以最后一次声明的名称与位置为准。
        Map<String, Map<String, PastItem>> past = new HashMap<>();
        for (ServiceDictionary previous : permissions.previousDictionaries()) {
            Map<String, PastItem> known = past.computeIfAbsent(
                    ResourceManifest.qualifiedCode(
                            previous.service(), previous.dictionary().code()),
                    key -> new LinkedHashMap<>());
            List<DictionaryItem> declared = previous.dictionary().items();
            for (int i = 0; i < declared.size(); i++) {
                known.putIfAbsent(
                        declared.get(i).code(), new PastItem(declared.get(i).name(), i + 1));
            }
        }
        return permissions.currentDictionaries().stream()
                .map(current -> {
                    String key = ResourceManifest.qualifiedCode(
                            current.service(), current.dictionary().code());
                    return merge(
                            current.service(),
                            current.dictionary(),
                            settings.getOrDefault(key, List.of()),
                            past.getOrDefault(key, Map.of()));
                })
                .toList();
    }

    /**
     * 一个服务的参数及在租户中的生效值，不校验权限，供业务服务读取自己的参数：其他服务的参数与它无关，不返回。
     *
     * @param service 声明参数的服务名
     * @return 按声明顺序排列，生效值规则见 {@link #allParameters(UUID)}
     */
    public List<TenantParameter> parameters(UUID tenantId, String service) {
        return allParameters(tenantId).stream()
                .filter(parameter -> parameter.service().equals(service))
                .toList();
    }

    /**
     * 租户的全部参数及生效值。覆盖值只在参数仍可修改且仍满足声明时生效，否则为默认值：
     * 服务的新版本可能收紧约束、取消可修改或删除参数，覆盖值保留但不再生效。按服务名、声明顺序排列。
     */
    private List<TenantParameter> allParameters(UUID tenantId) {
        Map<String, ParameterOverrideRow> values = new HashMap<>();
        for (ParameterOverrideRow row : overrides.list(tenantId)) {
            values.put(ResourceManifest.qualifiedCode(row.service(), row.code()), row);
        }
        List<TenantDictionary> dictionaries = dictionaries(tenantId);
        return permissions.currentParameters().stream()
                .map(current -> {
                    ParameterOverrideRow row = values.get(ResourceManifest.qualifiedCode(
                            current.service(), current.parameter().code()));
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
        return allParameters(actor.tenantId()).stream()
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
            history.append(actor, service, dictionary, "新增字典项 " + full);
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
            if (name.equals(item.name()) && sort == item.sort()) {
                return item;
            }
            // 名称不变时不查重名：服务新版本可能引入与该项同名的内置项，冲突项须仍能调整排序。
            if (item.selectable() && !name.equals(item.name())) {
                requireNameFree(current, name, code);
            }
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
            List<String> fields = new ArrayList<>();
            if (!name.equals(item.name())) {
                fields.add("显示名");
            }
            if (sort != item.sort()) {
                fields.add("排序");
            }
            history.append(actor, service, dictionary, "修改字典项 " + code + "：" + String.join("、", fields));
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
            if (enabled == item.selectable()) {
                return item;
            }
            if (enabled) {
                requireNameFree(current, item.name(), code);
            } else {
                requireUnused(actor.tenantId(), service, dictionary, code);
            }
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
            history.append(actor, service, dictionary, (enabled ? "启用字典项 " : "停用字典项 ") + code);
            changed(actor, itemAudit(actor, action, service, dictionary, code));
            return new TenantDictionaryItem(code, item.name(), item.sort(), item.custom(), status(enabled));
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

    /**
     * 恢复默认值；没有覆盖值时不做修改，也不递增版本。{@code dictionary} 类型的默认值已被租户停用时拒绝，否则生效值会落在停用项上。
     */
    public void resetParameter(TenantActor actor, String service, String code) {
        requireManager(actor);
        ServiceParameter parameter = requireEditable(service, code);
        transactions.executeWithoutResult(status -> {
            String dictionary = parameter.parameter().dictionary();
            // 与停用共用字典锁：检查默认值可选与删除覆盖值之间，默认值对应的项不会被停用。
            if (dictionary != null) {
                items.lockDictionary(actor.tenantId(), service, dictionary);
            }
            if (overrides.delete(actor.tenantId(), service, code, actor.identityId()) == 0) {
                return;
            }
            if (dictionary != null) {
                requireDefaultSelectable(actor.tenantId(), parameter);
            }
            changed(actor, parameterAudit(actor, "parameter.reset", service, code));
        });
    }

    private void requireDefaultSelectable(UUID tenantId, ServiceParameter current) {
        Parameter parameter = current.parameter();
        dictionaries(tenantId).stream()
                .filter(dictionary -> dictionary.service().equals(current.service())
                        && dictionary.code().equals(parameter.dictionary()))
                .flatMap(dictionary -> dictionary.items().stream())
                .filter(item -> item.code().equals(parameter.defaultValue()) && !item.selectable())
                .findFirst()
                .ifPresent(item -> {
                    // 抛出后事务回滚，覆盖值保留。
                    throw new ForgeException(ReferenceDataErrorCode.DEFAULT_UNAVAILABLE, parameter.name(), item.name());
                });
    }

    public List<DictionaryHistoryMetadataResponse> dictionaryHistory(
            TenantActor actor, String service, String dictionary) {
        roles.requirePermission(actor.membershipId(), IamPermissions.DICTIONARY_HISTORY_READ);
        Map<UUID, Optional<String>> names = new HashMap<>();
        return history.list(actor.tenantId(), service, dictionary).stream()
                .map(version -> new DictionaryHistoryMetadataResponse(
                        version.version(),
                        version.summary(),
                        version.changedBy(),
                        version.changedBy() == null
                                ? null
                                : names.computeIfAbsent(version.changedBy(), identities::findDisplayName)
                                        .orElse(null),
                        version.changedAt()))
                .toList();
    }

    public DictionaryHistoryDetailResponse dictionaryHistoryVersion(
            TenantActor actor, String service, String dictionary, long version) {
        roles.requirePermission(actor.membershipId(), IamPermissions.DICTIONARY_HISTORY_READ);
        var saved = historyVersion(actor, service, dictionary, version);
        var current = dictionaries(actor.tenantId()).stream()
                .filter(item -> item.service().equals(service) && item.code().equals(dictionary))
                .findFirst()
                .orElse(null);
        var manifest = permissions.currentDictionaries().stream()
                .filter(item -> item.service().equals(service)
                        && item.dictionary().code().equals(dictionary))
                .map(ServiceDictionary::dictionary)
                .findFirst()
                .orElse(null);
        return new DictionaryHistoryDetailResponse(
                saved.version(),
                saved.summary(),
                saved.changedBy(),
                saved.changedBy() == null
                        ? null
                        : identities.findDisplayName(saved.changedBy()).orElse(null),
                saved.changedAt(),
                saved.snapshot(),
                current,
                manifest);
    }

    public DictionaryHistoryDiffResponse dictionaryHistoryDiff(
            TenantActor actor, String service, String dictionary, long from, long to) {
        roles.requirePermission(actor.membershipId(), IamPermissions.DICTIONARY_HISTORY_READ);
        var before = historyVersion(actor, service, dictionary, from).snapshot();
        var after = historyVersion(actor, service, dictionary, to).snapshot();
        Map<String, DictionaryHistoryItem> previous =
                before.stream().collect(Collectors.toMap(DictionaryHistoryItem::code, item -> item));
        Map<String, DictionaryHistoryItem> next =
                after.stream().collect(Collectors.toMap(DictionaryHistoryItem::code, item -> item));
        var added = after.stream()
                .filter(item -> !previous.containsKey(item.code()))
                .toList();
        var removed =
                before.stream().filter(item -> !next.containsKey(item.code())).toList();
        var changed = after.stream()
                .filter(item -> previous.containsKey(item.code()) && !item.equals(previous.get(item.code())))
                .map(item -> new DictionaryHistoryDiffResponse.Change(previous.get(item.code()), item))
                .toList();
        return new DictionaryHistoryDiffResponse(from, to, added, removed, changed);
    }

    private DictionaryHistoryResponse historyVersion(
            TenantActor actor, String service, String dictionary, long version) {
        return history.list(actor.tenantId(), service, dictionary).stream()
                .filter(item -> item.version() == version)
                .findFirst()
                .orElseThrow(() -> new ForgeException(ReferenceDataErrorCode.HISTORY_NOT_FOUND));
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
        if (!current.extensible()) {
            throw new ForgeException(ReferenceDataErrorCode.DICTIONARY_READ_ONLY);
        }
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
        if (taken) {
            throw new ForgeException(ReferenceDataErrorCode.ITEM_NAME_TAKEN, name);
        }
    }

    /**
     * 字典项是本服务某个 {@code dictionary} 类型参数的生效值时不能停用：覆盖值指向停用项会回落到默认值，而默认值停用后参数就没有可选的生效值。
     * 不可修改的参数也算在内，它的默认值租户改不掉。
     */
    private void requireUnused(UUID tenantId, String service, String dictionary, String code) {
        allParameters(tenantId).stream()
                .filter(parameter -> parameter.service().equals(service)
                        && "dictionary".equals(parameter.type())
                        && dictionary.equals(parameter.dictionary())
                        && code.equals(parameter.value()))
                .findFirst()
                .ifPresent(parameter -> {
                    throw new ForgeException(
                            ReferenceDataErrorCode.ITEM_IN_USE, parameter.name(), parameter.tenantEditable() ? 1 : 0);
                });
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
        if (!"dictionary".equals(parameter.type())) {
            return true;
        }
        return dictionaries.stream()
                .filter(dictionary -> dictionary.service().equals(current.service())
                        && dictionary.code().equals(parameter.dictionary()))
                .flatMap(dictionary -> dictionary.items().stream())
                .anyMatch(item -> item.selectable() && item.code().equals(value));
    }

    private void changed(TenantActor actor, AuditEvent.Builder audit) {
        // 提交后才递增：提交前递增，业务服务可能在提交前重新拉取到旧数据并记下新版本，之后再也不会刷新。
        String key = ReferenceDataKeys.version(actor.tenantId());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    redis.opsForValue().increment(key);
                } catch (RuntimeException error) {
                    // 修改已提交，不能因 Redis 失败而报错；业务服务的缓存过期后读到新值。
                    log.warn("Failed to advance reference data version {}", key, error);
                }
            }
        });
        audits.record(audit.build());
    }

    private static AuditEvent.Builder itemAudit(
            TenantActor actor, String action, String service, String dictionary, String code) {
        // 字典项编码同样不含冒号（自定义项前缀为 custom.），接在服务限定的字典编码后仍可拆分。
        return audit(
                actor,
                action,
                "dictionaryItem",
                ResourceManifest.qualifiedCode(ResourceManifest.qualifiedCode(service, dictionary), code));
    }

    private static AuditEvent.Builder parameterAudit(TenantActor actor, String action, String service, String code) {
        return audit(actor, action, "parameter", ResourceManifest.qualifiedCode(service, code));
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
        for (DictionaryItemRow row : rows) {
            byCode.put(row.code(), row);
        }
        List<TenantDictionaryItem> merged = new ArrayList<>();
        Map<String, Boolean> declared = new HashMap<>();
        List<DictionaryItem> builtIns = dictionary.items();
        for (int i = 0; i < builtIns.size(); i++) {
            DictionaryItem item = builtIns.get(i);
            declared.put(item.code(), true);
            DictionaryItemRow row = extensible ? byCode.get(item.code()) : null;
            merged.add(new TenantDictionaryItem(
                    item.code(),
                    name(row, item.name()),
                    row == null || row.sortOrder() == null ? i + 1 : row.sortOrder(),
                    false,
                    status(row == null || row.enabled())));
        }
        past.forEach((code, item) -> {
            if (declared.containsKey(code)) {
                return;
            }
            DictionaryItemRow row = byCode.get(code);
            merged.add(new TenantDictionaryItem(
                    code, name(row, item.name()), item.sort(), false, TenantDictionaryItem.RETIRED));
        });
        for (DictionaryItemRow row : rows) {
            if (!row.custom()) {
                continue;
            }
            String status = extensible ? status(row.enabled()) : TenantDictionaryItem.RETIRED;
            merged.add(new TenantDictionaryItem(row.code(), row.name(), row.sortOrder(), true, status));
        }
        // 服务新版本新增的内置项可能与租户已有的可选项重名，不自动处理，标记出来提示租户改名。
        Map<String, Long> selectableNames = merged.stream()
                .filter(TenantDictionaryItem::selectable)
                .collect(Collectors.groupingBy(TenantDictionaryItem::name, Collectors.counting()));
        List<TenantDictionaryItem> flagged = merged.stream()
                .map(item -> item.selectable() && selectableNames.get(item.name()) > 1
                        ? new TenantDictionaryItem(
                                item.code(), item.name(), item.sort(), item.custom(), item.status(), true)
                        : item)
                .sorted(ITEM_ORDER)
                .toList();
        return new TenantDictionary(service, dictionary.code(), dictionary.name(), extensible, flagged);
    }

    // 内置项的设置行名称留空时沿用声明的名称。
    private static String name(DictionaryItemRow row, String declared) {
        return row == null || row.name() == null ? declared : row.name();
    }

    private static String status(boolean enabled) {
        return enabled ? TenantDictionaryItem.ENABLED : TenantDictionaryItem.DISABLED;
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

    private record PastItem(String name, int sort) {}
}
