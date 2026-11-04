package io.saasforge.sdk.core;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Business Time Zone 配置 {@code forge.business-zone}：全平台统一、用于确定业务日期的时区，放在 Nacos 共享配置中。
 *
 * <p>只有用到业务日期的能力（Business Number 的日期段、定时任务的 cron）才需要它，因此绑定时允许缺省，由使用方在启动时调用
 * {@link #require(String)}，漏配即启动失败。代码中不设默认值：服务器时区不能代替 Business Time Zone。
 *
 * <p>使用方以 {@code @EnableConfigurationProperties(BusinessZoneProperties.class)} 注册，多个模块重复注册时只绑定一次。
 *
 * @param businessZone Business Time Zone，如 {@code Asia/Shanghai}；未配置时为 null
 */
@ConfigurationProperties("forge")
public record BusinessZoneProperties(ZoneId businessZone) {
    /**
     * 返回 Business Time Zone，未配置时抛出异常。
     *
     * @param usage 需要 Business Time Zone 的能力，写入异常信息便于排查，如 {@code "Business Number"}
     * @return Business Time Zone
     * @throws IllegalStateException 未配置 {@code forge.business-zone}
     */
    public ZoneId require(String usage) {
        if (businessZone == null) {
            throw new IllegalStateException("forge.business-zone must be configured: " + usage + " needs it");
        }
        return businessZone;
    }
}
