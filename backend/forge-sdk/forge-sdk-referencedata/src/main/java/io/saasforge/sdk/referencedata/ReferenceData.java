package io.saasforge.sdk.referencedata;

import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.referencedata.ReferenceDataClient.Snapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 读取当前租户生效的 Dictionary 与 Parameter：内置项合并租户的设置与自定义项，默认值合并租户的覆盖值，由 iam 计算。
 *
 * <p>结果按租户缓存在进程内：iam 在租户修改后发布 {@code ReferenceDataChanged}，本实例收到后清除该租户的缓存；缓存另有
 * {@code forge.reference-data.cache-ttl} 的过期时间，兜底漏收的事件。所有方法都读取 {@link CurrentTenant} 绑定的租户。
 *
 * <p>字典编码写作 {@code 服务:编码} 引用其他服务的字典，不带服务前缀即本服务（{@code spring.application.name}）的字典。
 * 参数只能读本服务声明的，按声明类型取值，类型不符或编码未声明时抛出 {@link IllegalArgumentException}（属于编码错误）。
 */
public final class ReferenceData {
    private final Function<UUID, Snapshot> loader;
    private final String service;
    private final Duration ttl;
    private final Clock clock;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    ReferenceData(Function<UUID, Snapshot> loader, String service, Duration ttl, Clock clock) {
        this.loader = loader;
        this.service = service;
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * 字典的全部项，含停用与下线的项，供显示历史数据的名称。
     *
     * @param dictionary 字典编码，可写作 {@code 服务:编码}
     * @return 未下线的项按排序与编码排列，下线项排在最后
     * @throws IllegalArgumentException 服务当前的 Resource Manifest 未声明该字典
     * @throws ForgeException 503 {@code system.unavailable} 缓存未命中且 iam 不可用
     */
    public List<TenantDictionaryItem> items(String dictionary) {
        String key = ResourceManifest.qualifiedDictionary(service, dictionary);
        List<TenantDictionaryItem> items = snapshot().dictionaries().get(key);
        if (items == null) throw new IllegalArgumentException("Unknown Dictionary: " + key);
        return items;
    }

    /**
     * 字典的可选项，即新写入的业务数据可以使用的项。
     *
     * @param dictionary 字典编码，可写作 {@code 服务:编码}
     * @return 可选项，顺序同 {@link #items(String)}
     * @throws IllegalArgumentException 服务当前的 Resource Manifest 未声明该字典
     */
    public List<TenantDictionaryItem> selectableItems(String dictionary) {
        return items(dictionary).stream()
                .filter(TenantDictionaryItem::selectable)
                .toList();
    }

    /**
     * 编码是否为字典的可选项。
     *
     * @param dictionary 字典编码，可写作 {@code 服务:编码}
     * @param itemCode 待检查的字典项编码
     * @return 编码不存在、已停用或已下线时为 false
     * @throws IllegalArgumentException 服务当前的 Resource Manifest 未声明该字典
     */
    public boolean isSelectable(String dictionary, String itemCode) {
        return items(dictionary).stream()
                .anyMatch(item -> item.selectable() && item.code().equals(itemCode));
    }

    /**
     * {@code string} 类型参数的生效值。
     *
     * @param code 本服务声明的参数编码
     * @return 生效值
     */
    public String stringParameter(String code) {
        return (String) parameter(code, "string");
    }

    /**
     * {@code integer} 类型参数的生效值。
     *
     * @param code 本服务声明的参数编码
     * @return 生效值
     */
    public long integerParameter(String code) {
        return new BigDecimal(parameter(code, "integer").toString()).longValueExact();
    }

    /**
     * {@code decimal} 类型参数的生效值。
     *
     * @param code 本服务声明的参数编码
     * @return 生效值
     */
    public BigDecimal decimalParameter(String code) {
        return new BigDecimal(parameter(code, "decimal").toString());
    }

    /**
     * {@code duration} 类型参数的生效值。
     *
     * @param code 本服务声明的参数编码
     * @return 生效值
     */
    public Duration durationParameter(String code) {
        return Duration.parse((String) parameter(code, "duration"));
    }

    /**
     * {@code boolean} 类型参数的生效值。
     *
     * @param code 本服务声明的参数编码
     * @return 生效值
     */
    public boolean booleanParameter(String code) {
        return (Boolean) parameter(code, "boolean");
    }

    /**
     * {@code dictionary} 类型参数的生效值。
     *
     * @param code 本服务声明的参数编码
     * @return 所属字典的一个字典项编码
     */
    public String dictionaryParameter(String code) {
        return (String) parameter(code, "dictionary");
    }

    /** 本服务名（{@code spring.application.name}），不带服务前缀的字典编码与全部参数都属于它。 */
    String service() {
        return service;
    }

    /** 清除租户的缓存，下次读取时重新从 iam 拉取；收到 {@code ReferenceDataChanged} 时调用，重复调用无副作用。 */
    void evict(UUID tenantId) {
        cache.remove(tenantId);
    }

    private Object parameter(String code, String type) {
        ReferenceDataClient.Parameter parameter = snapshot().parameters().get(code);
        if (parameter == null)
            throw new IllegalArgumentException("Unknown Parameter: " + ResourceManifest.qualifiedCode(service, code));
        if (!parameter.type().equals(type)) {
            throw new IllegalArgumentException(
                    "Parameter " + code + " is of type " + parameter.type() + ", not " + type);
        }
        return parameter.value();
    }

    private Snapshot snapshot() {
        UUID tenantId = CurrentTenant.require();
        while (true) {
            Entry entry = cache.computeIfAbsent(tenantId, key -> new Entry());
            Snapshot snapshot = entry.get(tenantId);
            if (snapshot != null) return snapshot;
            cache.remove(tenantId, entry);
        }
    }

    /**
     * 一个租户的缓存项。拉取在项内串行，不占用 Map 的锁；清除只把项移出 Map，清除前已开始的拉取把结果写进已移出的项，
     * 之后的读取会新建项重新拉取，不会把事件之前的旧值放回缓存。
     */
    private final class Entry {
        private Snapshot snapshot;
        private Instant expiresAt;

        /** 返回未过期的数据，已过期时返回 null，由调用方移出后重新拉取。 */
        synchronized Snapshot get(UUID tenantId) {
            if (snapshot == null) {
                snapshot = loader.apply(tenantId);
                expiresAt = clock.instant().plus(ttl);
                return snapshot;
            }
            return clock.instant().isBefore(expiresAt) ? snapshot : null;
        }
    }
}
