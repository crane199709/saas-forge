package io.saasforge.sdk.abac;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** 把调用方传入的属性值规范化为 {@link AttributeType} 约定的内存表示；求值器与编译器共用，保证两边看到同一个值。 */
final class AttributeValues {
    // PostgreSQL 16 起 ltree 标签允许连字符；空路径虽合法但会匹配全部数据，按非法处理
    private static final Pattern LTREE = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*");

    private AttributeValues() {}

    /** 从主体与环境上下文取值；集合属性缺失视为空集合，使「无部门」的成员看不到任何部门数据而不是报错。 */
    static Object context(AttributeDefinition attribute, Map<String, ?> context) {
        Object raw = context.get(attribute.name());
        if (!attribute.multiValued()) {
            return scalar(attribute.type(), raw, attribute.name());
        }
        return raw == null ? List.of() : list(attribute.type(), raw, attribute.name(), false);
    }

    /** 从资源对象取值；资源侧集合保留 {@code null} 与 {@code null} 元素，与数据库数组列的语义对齐。 */
    static Object resource(AttributeDefinition attribute, Map<String, ?> resource) {
        Object raw = resource.get(attribute.name());
        if (!attribute.multiValued()) {
            return scalar(attribute.type(), raw, attribute.name());
        }
        return raw == null ? null : list(attribute.type(), raw, attribute.name(), true);
    }

    static Object scalar(AttributeType type, Object raw, String name) {
        if (raw == null) {
            return null;
        }
        Object value = switch (type) {
            case STRING -> raw instanceof String ? raw : null;
            case NUMBER -> number(raw);
            case BOOLEAN -> raw instanceof Boolean ? raw : null;
            case UUID -> uuid(raw);
            case LTREE -> raw instanceof String path && LTREE.matcher(path).matches() ? path : null;
        };
        if (value == null) {
            throw new IllegalArgumentException("属性 " + name + " 的值不是合法的 " + type + "：" + raw);
        }
        return value;
    }

    static List<Object> list(AttributeType type, Object raw, String name, boolean allowNullElements) {
        if (!(raw instanceof Collection<?> collection)) {
            throw new IllegalArgumentException("属性 " + name + " 必须是集合：" + raw);
        }
        var values = new ArrayList<>(collection.size());
        for (Object element : collection) {
            if (element == null && !allowNullElements) {
                throw new IllegalArgumentException("属性 " + name + " 的集合不得包含 null");
            }
            values.add(scalar(type, element, name));
        }
        return Collections.unmodifiableList(values);
    }

    /** 数值按大小比较而非 {@code equals}，避免 {@code 1} 与 {@code 1.0} 被判为不等，与 SQL 的 numeric 比较一致。 */
    static boolean same(AttributeType type, Object left, Object right) {
        if (type == AttributeType.NUMBER) {
            return ((BigDecimal) left).compareTo((BigDecimal) right) == 0;
        }
        return left.equals(right);
    }

    private static BigDecimal number(Object raw) {
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (raw instanceof Long || raw instanceof Integer || raw instanceof Short || raw instanceof Byte) {
            return BigDecimal.valueOf(((Number) raw).longValue());
        }
        if ((raw instanceof Double || raw instanceof Float) && Double.isFinite(((Number) raw).doubleValue())) {
            // 经 toString 转换，避免 0.1 这类二进制浮点误差进入比较
            return new BigDecimal(raw.toString());
        }
        return null;
    }

    private static UUID uuid(Object raw) {
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        if (raw instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }
}
