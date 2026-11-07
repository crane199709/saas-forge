package io.saasforge.sdk.notification;

import java.net.URI;

/**
 * 指向 Sub-App 内的站内路由，不接受外部 URL。
 * @param appCode App Registry 中登记的 Sub-App 编码，最长 64 字符
 * @param route 以单个斜杠开头的路由，最长 2048 字符，可包含查询参数或片段
 */
public record NotificationLink(String appCode, String route) {
    /** 验证登记编码与 URI 路由格式，不把内容链接当作任意 URL。 */
    public NotificationLink {
        if (appCode == null || !appCode.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("appCode must match [a-z][a-z0-9-]{0,63}");
        }
        NotificationValidation.text(route, "route", 2048);
        if (!route.startsWith("/") || route.startsWith("//")) {
            throw new IllegalArgumentException("route must be a Sub-App path beginning with a single /");
        }
        try {
            URI.create(route);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("route must be a valid URI path", invalid);
        }
    }
}
