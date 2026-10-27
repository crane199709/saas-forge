package io.saasforge.sdk.web;

import io.saasforge.sdk.core.api.FrameworkMessages;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.MessageSource;

/**
 * 按错误码生成面向最终用户的 {@code userTip}：先查服务自己的 {@code messages*.properties}，再查 SDK 自带的框架级文案，都没有时回退为通用提示。
 */
public class UserTipResolver {

    private final MessageSource messageSource;

    /**
     * @param messageSource 服务的 MessageSource，SDK 不替换它，服务的文案配置保持原样
     */
    public UserTipResolver(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * 生成 {@code userTip}，查不到时返回通用提示，不向用户展示 code。
     *
     * @param code 错误码
     * @param args 文案占位符参数
     * @param locale 请求语言
     * @return 可直接展示的文案
     */
    public String resolve(String code, Object[] args, Locale locale) {
        return find(code, args, locale).orElseGet(() -> FrameworkMessages.fallback(locale));
    }

    /**
     * 查找 {@code code} 对应的文案。
     *
     * @param code 错误码或校验错误码
     * @param args 文案占位符参数
     * @param locale 请求语言
     * @return 文案，服务与 SDK 都未定义时为空
     */
    public Optional<String> find(String code, Object[] args, Locale locale) {
        String message = messageSource.getMessage(code, args, null, locale);
        // 服务开启 useCodeAsDefaultMessage 时查不到会返回 code 本身，同样视为未定义
        if (message != null && !message.equals(code)) {
            return Optional.of(message);
        }
        return FrameworkMessages.find(code, locale);
    }
}
