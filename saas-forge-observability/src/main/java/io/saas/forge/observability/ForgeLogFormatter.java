package io.saas.forge.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;

/** 仅输出受控结构化字段；框架消息、异常消息、MDC 与堆栈不能绕过敏感值白名单。 */
public final class ForgeLogFormatter implements StructuredLogFormatter<ILoggingEvent> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String service;
    private final String environment;

    public ForgeLogFormatter(Environment environment) {
        this.service = environment.getProperty("spring.application.name", "unknown");
        this.environment = environment.getProperty("saas.forge.environment", "unknown");
    }

    @Override
    public String format(ILoggingEvent log) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("timestamp", Instant.ofEpochMilli(log.getTimeStamp()).toString());
        fields.put("level", log.getLevel().toString());
        fields.put("service", service);
        fields.put("environment", environment);
        fields.put("event", log.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR)
                ? "application.failure" : "application.lifecycle");
        fields.put("message", "Application diagnostic");
        fields.put("schemaVersion", 1);
        // 只有本模块的日志入口能提供上下文字段，任意第三方键值对一律忽略。
        if (ForgeLogs.class.getName().equals(log.getLoggerName()) && log.getKeyValuePairs() != null) {
            for (var pair : log.getKeyValuePairs()) {
                if (java.util.Set.of("event", "traceId", "spanId", "requestId", "http").contains(pair.key))
                    fields.put(pair.key, pair.value);
            }
        }
        if (log.getThrowableProxy() != null) {
            fields.put("exception", Map.of("type", log.getThrowableProxy().getClassName(),
                    "code", "APPLICATION_FAILURE", "message", "Application operation failed"));
        }
        try { return JSON.writeValueAsString(fields) + "\n"; }
        catch (JsonProcessingException impossible) { throw new IllegalStateException("日志字段序列化失败", impossible); }
    }
}
