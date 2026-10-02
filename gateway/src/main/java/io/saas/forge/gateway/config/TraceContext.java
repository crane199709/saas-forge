package io.saas.forge.gateway.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gateway 内部使用的 W3C Trace Context；只延续格式正确且非零的调用方 Trace ID。
 */
final class TraceContext {

    static final String REQUEST_ATTRIBUTE = TraceContext.class.getName();

    static final String TRACEPARENT_HEADER = "traceparent";

    static final String TRACESTATE_HEADER = "tracestate";

    private static final Pattern TRACEPARENT = Pattern.compile(
            "^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(?:-([0-9a-f]+(?:-[0-9a-f]+)*))?$");

    private final String traceparent;

    private final String traceId;

    private final boolean continued;

    private TraceContext(String traceparent, String traceId, boolean continued) {
        this.traceparent = traceparent;
        this.traceId = traceId;
        this.continued = continued;
    }

    static TraceContext establish(HttpServletRequest request) {
        Object established = request.getAttribute(io.saas.forge.observability.HttpTraceFilter.TRACEPARENT_ATTRIBUTE);
        if (established instanceof String parent) return new TraceContext(parent, parent.substring(3, 35),
                Boolean.TRUE.equals(request.getAttribute(io.saas.forge.observability.HttpTraceFilter.CONTINUED_ATTRIBUTE)));
        List<String> traceparents = Collections.list(request.getHeaders(TRACEPARENT_HEADER));
        if (traceparents.size() == 1) {
            Matcher matcher = TRACEPARENT.matcher(traceparents.get(0));
            if (matcher.matches() && isValid(matcher)) {
                return new TraceContext(traceparents.get(0), matcher.group(2), true);
            }
        }
        String traceId = randomNonZeroHex(32);
        return new TraceContext("00-" + traceId + "-" + randomNonZeroHex(16) + "-01", traceId, false);
    }

    static TraceContext current(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        if (value instanceof TraceContext context) {
            return context;
        }
        return establish(request);
    }

    String traceparent() {
        return traceparent;
    }

    String traceId() {
        return traceId;
    }

    boolean continued() {
        return continued;
    }

    private static boolean isValid(Matcher matcher) {
        String version = matcher.group(1);
        String traceId = matcher.group(2);
        String parentId = matcher.group(3);
        return !"ff".equals(version) && !allZeroes(traceId) && !allZeroes(parentId)
                && (!"00".equals(version) || matcher.group(5) == null);
    }

    private static String randomNonZeroHex(int length) {
        String value;
        do {
            value = UUID.randomUUID().toString().replace("-", "").substring(0, length);
        } while (allZeroes(value));
        return value;
    }

    private static boolean allZeroes(String value) {
        return value.chars().allMatch(character -> character == '0');
    }
}
