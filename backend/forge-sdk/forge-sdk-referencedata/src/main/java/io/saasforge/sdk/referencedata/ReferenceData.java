package io.saasforge.sdk.referencedata;

import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.ReferenceDataKeys;
import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.referencedata.ReferenceDataClient.Snapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 读取当前租户生效的 Dictionary 与 Parameter：内置项合并租户的设置与自定义项，默认值合并租户的覆盖值，由 iam 计算。
 *
 * <p>结果按租户缓存在进程内。iam 在租户修改提交后递增 Redis 中该租户的版本号（{@link ReferenceDataKeys#version(UUID)}，做法同
 * ADR-0005 的 Membership version），每次读取先比对版本号（一次 Redis 读），与缓存时不同即重新拉取，服务的每个实例都不需要收到通知；
 * 缓存另有 {@code forge.reference-data.cache-ttl} 的过期时间，兜底 iam 递增失败或版本号缺失。所有方法都读取 {@link CurrentTenant}
 * 绑定的租户。
 *
 * <p>字典编码写作 {@code 服务:编码} 引用其他服务的字典，不带服务前缀即本服务（{@code spring.application.name}）的字典。
 * 参数只能读本服务声明的，按声明类型取值，类型不符或编码未声明时抛出 {@link IllegalArgumentException}（属于编码错误）。
 */
public final class ReferenceData {
    private static final Logger log = LoggerFactory.getLogger(ReferenceData.class);

    private final Function<UUID, Snapshot> loader;
    private final Function<UUID, String> versions;
    private final String service;
    private final Duration ttl;
    private final Clock clock;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    /**
     * 创建读取入口。
     *
     * @param versions 读取租户当前的版本号，键缺失时返回 null
     */
    ReferenceData(
            Function<UUID, Snapshot> loader,
            Function<UUID, String> versions,
            String service,
            Duration ttl,
            Clock clock) {
        this.loader = loader;
        this.versions = versions;
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
        if (items == null) {
            throw new IllegalArgumentException("Unknown Dictionary: " + key);
        }
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

    private Object parameter(String code, String type) {
        ReferenceDataClient.Parameter parameter = snapshot().parameters().get(code);
        if (parameter == null) {
            throw new IllegalArgumentException("Unknown Parameter: " + ResourceManifest.qualifiedCode(service, code));
        }
        if (!parameter.type().equals(type)) {
            throw new IllegalArgumentException(
                    "Parameter " + code + " is of type " + parameter.type() + ", not " + type);
        }
        return parameter.value();
    }

    private Snapshot snapshot() {
        UUID tenantId = CurrentTenant.require();
        // 先读版本再拉取：拉取期间 iam 又有修改时，记下的是旧版本，下次读取会再拉取一次，不会把旧数据当作新版本缓存。
        Version version = version(tenantId);
        while (true) {
            Entry entry = cache.computeIfAbsent(tenantId, key -> new Entry());
            Snapshot snapshot = entry.get(tenantId, version);
            if (snapshot != null) {
                return snapshot;
            }
            cache.remove(tenantId, entry);
        }
    }

    private Version version(UUID tenantId) {
        try {
            return new Version(true, versions.apply(tenantId));
        } catch (RuntimeException error) {
            // Redis 不可用时不中断业务读取：沿用未过期的缓存，由过期兜底。
            log.warn("Failed to read reference data version of tenant {}", tenantId, error);
            return new Version(false, null);
        }
    }

    /**
     * Redis 中租户的版本号。
     *
     * @param known 是否读到了 Redis；读取失败时不比对版本
     * @param value 版本号，键缺失时为 null
     */
    private record Version(boolean known, String value) {}

    /**
     * 一个租户的缓存项。拉取在项内串行，不占用 Map 的锁；过时的项只被移出 Map，同时进行中的拉取把结果写进已移出的项，
     * 之后的读取会新建项重新拉取。
     */
    private final class Entry {
        private Snapshot snapshot;
        private String version;
        private Instant expiresAt;

        /** 返回未过期且版本未变的数据，否则返回 null，由调用方移出后重新拉取。 */
        synchronized Snapshot get(UUID tenantId, Version current) {
            if (snapshot == null) {
                snapshot = loader.apply(tenantId);
                version = current.value();
                expiresAt = clock.instant().plus(ttl);
                return snapshot;
            }
            if (!clock.instant().isBefore(expiresAt)) {
                return null;
            }
            if (current.known() && !Objects.equals(current.value(), version)) {
                return null;
            }
            return snapshot;
        }
    }
}
