package io.saasforge.sdk.security;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Pattern;
import org.slf4j.helpers.MessageFormatter;

/** 替换 Logback 的三个消息别名，支持 {@code field.path={}}、唯一字段短名与显式安全包装。 */
public final class FieldMaskingMessageConverter extends ClassicConverter {
    private static final Pattern NAMED = Pattern.compile("([A-Za-z_][A-Za-z0-9_.]*)\\s*[=:]\\s*$");
    private boolean warned;

    @Override
    public String convert(ILoggingEvent event) {
        var args = event.getArgumentArray();
        if (args == null || args.length == 0) {
            return event.getFormattedMessage();
        }
        Object[] safe = args.clone();
        String message = event.getMessage();
        int from = 0;
        for (int i = 0; i < safe.length; i++) {
            int placeholder = message.indexOf("{}", from);
            while (placeholder > 0 && message.charAt(placeholder - 1) == '\\') {
                from = placeholder + 2;
                placeholder = message.indexOf("{}", from);
            }
            String path = null;
            Object value = safe[i];
            if (value instanceof SensitiveLogValue marked) {
                path = marked.path();
                value = marked.value();
            } else if (placeholder >= 0) {
                var match = NAMED.matcher(message.substring(from, placeholder));
                if (match.find()) {
                    path = FieldMaskingLogRuntime.path(match.group(1));
                }
            }
            if (path != null) {
                safe[i] = FieldMaskingLogRuntime.mask(path, value, event.getMDCPropertyMap());
                warnIfDropped(safe[i]);
            }
            if (placeholder >= 0) {
                from = placeholder + 2;
            }
        }
        return MessageFormatter.arrayFormat(message, safe).getMessage();
    }

    private void warnIfDropped(Object value) {
        if (FieldMaskingLogRuntime.REDACTED.equals(value) && !warned) {
            warned = true;
            addWarn("Sensitive log value discarded because its masking policy is unavailable or failed");
        }
    }
}
