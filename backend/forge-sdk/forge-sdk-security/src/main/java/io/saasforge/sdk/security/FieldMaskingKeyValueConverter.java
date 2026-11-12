package io.saasforge.sdk.security;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** 防止 SLF4J fluent API 的结构化 key/value 经 {@code %kvp} 绕开消息脱敏。 */
public final class FieldMaskingKeyValueConverter extends ClassicConverter {
    private boolean warned;

    @Override
    public String convert(ILoggingEvent event) {
        if (event.getKeyValuePairs() == null) return "";
        String quote = "NONE".equals(getFirstOption()) ? "" : "SINGLE".equals(getFirstOption()) ? "'" : "\"";
        var result = new StringBuilder();
        for (var pair : event.getKeyValuePairs()) {
            String path = FieldMaskingLogRuntime.path(pair.key);
            Object value = pair.value;
            boolean explicitlySensitive = value instanceof SensitiveLogValue;
            if (value instanceof SensitiveLogValue marked) {
                path = marked.path();
                value = marked.value();
            }
            if (path != null || explicitlySensitive) {
                value = FieldMaskingLogRuntime.mask(path, value, event.getMDCPropertyMap());
                if (FieldMaskingLogRuntime.REDACTED.equals(value) && !warned) {
                    warned = true;
                    addWarn(
                            "Sensitive structured log value discarded because its masking policy is unavailable or failed");
                }
            }
            if (!result.isEmpty()) result.append(' ');
            result.append(pair.key).append('=').append(quote).append(value).append(quote);
        }
        return result.toString();
    }
}
