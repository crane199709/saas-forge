package io.saasforge.sdk.tenant;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 租户隔离配置，前缀 {@code forge.tenant}。
 *
 * @param ignoreTables 不按租户隔离的全局表（如全局字典），表名不区分大小写、不带 schema
 * @param writeGuard 按租户状态拒绝写入
 */
@ConfigurationProperties("forge.tenant")
public record TenantProperties(
        List<String> ignoreTables, @DefaultValue WriteGuard writeGuard) {
    /** 未配置时视为没有全局表，所有表都按租户隔离。 */
    public TenantProperties {
        ignoreTables = ignoreTables == null ? List.of() : List.copyOf(ignoreTables);
    }

    /**
     * {@link TenantWriteGuard} 配置，前缀 {@code forge.tenant.write-guard}。
     *
     * @param enabled 是否启用；服务使用 Redis 时才生效，关闭后任何状态的租户都可写入
     */
    public record WriteGuard(@DefaultValue("true") boolean enabled) {}
}
