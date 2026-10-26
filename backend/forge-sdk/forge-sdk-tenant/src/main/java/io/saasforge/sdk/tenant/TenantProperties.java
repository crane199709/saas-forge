package io.saasforge.sdk.tenant;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 租户隔离配置，前缀 {@code forge.tenant}。
 *
 * @param ignoreTables 不按租户隔离的全局表（如全局字典），表名不区分大小写、不带 schema
 */
@ConfigurationProperties("forge.tenant")
public record TenantProperties(List<String> ignoreTables) {
    /** 未配置时视为没有全局表，所有表都按租户隔离。 */
    public TenantProperties {
        ignoreTables = ignoreTables == null ? List.of() : List.copyOf(ignoreTables);
    }
}
