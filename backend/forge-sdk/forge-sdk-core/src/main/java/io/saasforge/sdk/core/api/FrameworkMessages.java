package io.saasforge.sdk.core.api;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * SDK 自带的框架级错误码文案。用独立的 ResourceBundle 而不是服务的 {@code messages*.properties}，服务无需任何配置即可解析，
 * 也不会与服务自己的文案相互覆盖。
 */
public final class FrameworkMessages {

    private static final String BUNDLE = "io.saasforge.sdk.core.api.forge-messages";
    private static final String FALLBACK_KEY = "forge.fallback";

    // 不回退到 JVM 默认语言：基础文件就是默认语言 zh-CN，未提供的语言统一落到它上面，结果不随部署机器的 locale 变化
    private static final ResourceBundle.Control NO_DEFAULT_LOCALE =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private FrameworkMessages() {}

    /**
     * 按错误码查文案。
     *
     * @param code 错误码或校验错误码
     * @param locale 请求语言；未提供该语言的文案时使用 zh-CN
     * @return 文案，SDK 未定义该 code 时为空
     */
    public static Optional<String> find(String code, Locale locale) {
        ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE, locale, NO_DEFAULT_LOCALE);
        try {
            return Optional.of(bundle.getString(code));
        } catch (MissingResourceException e) {
            return Optional.empty();
        }
    }

    /**
     * 查不到文案时的通用提示，不向用户展示 code。
     *
     * @param locale 请求语言
     * @return 通用提示
     */
    public static String fallback(Locale locale) {
        return find(FALLBACK_KEY, locale).orElseThrow();
    }
}
