package io.saasforge.sdk.abac;

import io.saasforge.sdk.abac.Expression.And;
import io.saasforge.sdk.abac.Expression.Comparison;
import io.saasforge.sdk.abac.Expression.Not;
import io.saasforge.sdk.abac.Expression.Or;
import io.saasforge.sdk.abac.Operand.AttributeRef;
import io.saasforge.sdk.abac.Operand.Literal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 把 JSON 表达式树解析为 {@link Expression}，并在加载时完成全部校验。
 *
 * <p>JSON 形式：
 *
 * <pre>{@code
 * {"and": [ ... ]}   {"or": [ ... ]}   {"not": { ... }}
 * {"attr": "resource.departmentPath", "op": "under", "value": {"ref": "subject.departmentPaths"}}
 * {"attr": "resource.amount", "op": "gte", "value": 100}
 * {"attr": "resource.ownerId", "op": "is_null"}
 * }</pre>
 *
 * <p>{@code value} 为字面量，或以 {@code {"ref": ...}} 引用主体、环境属性；不允许引用资源属性。
 */
public final class PolicyParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> COMPARISON_FIELDS = Set.of("attr", "op", "value");

    private PolicyParser() {}

    /**
     * 解析并校验 Data Policy。
     *
     * @param json JSON 表达式树
     * @param catalog 可引用的属性
     * @return 校验通过的表达式树
     * @throws PolicyValidationException 结构不合法、属性未声明、操作符未知或类型不符
     */
    public static Expression parse(String json, AttributeCatalog catalog) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new PolicyValidationException("", "不是合法的 JSON：" + e.getOriginalMessage());
        }
        return new PolicyParserRun(catalog).expression(root, "");
    }

    /** 携带属性目录的一次解析过程。 */
    private record PolicyParserRun(AttributeCatalog catalog) {
        Expression expression(JsonNode node, String path) {
            if (node == null || !node.isObject()) {
                throw new PolicyValidationException(path, "节点必须是 JSON 对象");
            }
            var fields = node.propertyNames();
            for (String logical : List.of("and", "or", "not")) {
                if (fields.contains(logical)) {
                    if (fields.size() != 1) {
                        throw new PolicyValidationException(path, "逻辑节点只能包含 " + logical + " 一个字段");
                    }
                    return logical(logical, node.get(logical), path + "/" + logical);
                }
            }
            return comparison(node, path);
        }

        private Expression logical(String kind, JsonNode value, String path) {
            if (kind.equals("not")) {
                return new Not(expression(value, path));
            }
            if (!value.isArray()) {
                throw new PolicyValidationException(path, kind + " 的值必须是数组");
            }
            var children = new ArrayList<Expression>();
            int index = 0;
            for (JsonNode child : value.values()) {
                children.add(expression(child, path + "/" + index++));
            }
            return kind.equals("and") ? new And(children) : new Or(children);
        }

        private Comparison comparison(JsonNode node, String path) {
            for (String field : node.propertyNames()) {
                if (!COMPARISON_FIELDS.contains(field)) {
                    throw new PolicyValidationException(path + "/" + field, "未知字段");
                }
            }
            AttributeDefinition attribute = attribute(node.get("attr"), path + "/attr");
            JsonNode opNode = node.get("op");
            if (opNode == null || !opNode.isString()) {
                throw new PolicyValidationException(path + "/op", "缺少操作符");
            }
            Operator operator = Operator.fromJson(opNode.stringValue())
                    .orElseThrow(() -> new PolicyValidationException(path + "/op", "未知操作符：" + opNode.stringValue()));
            checkAttribute(attribute, operator, path + "/attr");

            JsonNode valueNode = node.get("value");
            String valuePath = path + "/value";
            Shape shape = Shape.of(operator);
            if (shape == Shape.NONE) {
                if (valueNode != null) {
                    throw new PolicyValidationException(valuePath, operator.jsonName() + " 不接受 value");
                }
                return new Comparison(attribute, operator, null);
            }
            if (valueNode == null || valueNode.isNull()) {
                throw new PolicyValidationException(valuePath, operator.jsonName() + " 缺少 value；判空请用 is_null");
            }
            Operand operand = valueNode.isObject()
                    ? reference(valueNode, valuePath, attribute.type(), shape)
                    : literal(valueNode, valuePath, attribute.type(), shape);
            return new Comparison(attribute, operator, operand);
        }

        private AttributeDefinition attribute(JsonNode node, String path) {
            if (node == null || !node.isString()) {
                throw new PolicyValidationException(path, "缺少属性名");
            }
            return catalog.find(node.stringValue())
                    .orElseThrow(() -> new PolicyValidationException(path, "未知属性：" + node.stringValue()));
        }

        private static void checkAttribute(AttributeDefinition attribute, Operator operator, String path) {
            boolean valid = switch (operator) {
                case EQ, NE, IN, NIN -> !attribute.multiValued();
                case GT, GTE, LT, LTE -> !attribute.multiValued() && attribute.type() == AttributeType.NUMBER;
                case CONTAINS -> attribute.multiValued();
                case IS_NULL -> true;
                case UNDER -> !attribute.multiValued() && attribute.type() == AttributeType.LTREE;
            };
            if (!valid) {
                throw new PolicyValidationException(
                        path,
                        operator.jsonName() + " 不适用于" + (attribute.multiValued() ? "集合" : "单值") + " " + attribute.type()
                                + " 属性 " + attribute.name());
            }
        }

        private Operand reference(JsonNode node, String path, AttributeType expectedType, Shape shape) {
            if (node.propertyNames().size() != 1 || !node.has("ref")) {
                throw new PolicyValidationException(path, "属性引用只能包含 ref 一个字段");
            }
            AttributeDefinition referenced = attribute(node.get("ref"), path + "/ref");
            if (referenced.isResource()) {
                throw new PolicyValidationException(path + "/ref", "只能引用主体或环境属性：" + referenced.name());
            }
            if (referenced.type() != expectedType || !shape.accepts(referenced.multiValued())) {
                throw new PolicyValidationException(
                        path + "/ref", "引用的属性 " + referenced.name() + " 类型不符，期望" + shape.describe(expectedType));
            }
            return new AttributeRef(referenced);
        }

        private static Operand literal(JsonNode node, String path, AttributeType type, Shape shape) {
            if (!shape.accepts(node.isArray())) {
                throw new PolicyValidationException(path, "类型不符，期望" + shape.describe(type));
            }
            if (!node.isArray()) {
                return new Literal(scalar(node, path, type));
            }
            var values = new ArrayList<>();
            int index = 0;
            for (JsonNode element : node.values()) {
                values.add(scalar(element, path + "/" + index++, type));
            }
            return new Literal(Collections.unmodifiableList(values));
        }

        private static Object scalar(JsonNode node, String path, AttributeType type) {
            Object raw = switch (type) {
                case STRING, UUID, LTREE -> node.isString() ? node.stringValue() : null;
                case NUMBER -> node.isNumber() ? node.decimalValue() : null;
                case BOOLEAN -> node.isBoolean() ? node.booleanValue() : null;
            };
            if (raw == null) {
                throw new PolicyValidationException(path, "类型不符，期望 " + type + " 字面量：" + node);
            }
            try {
                return AttributeValues.scalar(type, raw, path);
            } catch (IllegalArgumentException e) {
                throw new PolicyValidationException(path, "类型不符，期望 " + type + " 字面量：" + node);
            }
        }
    }

    /** 操作符对 value 形态的要求。 */
    private enum Shape {
        NONE,
        SCALAR,
        LIST,
        SCALAR_OR_LIST;

        static Shape of(Operator operator) {
            return switch (operator) {
                case IS_NULL -> NONE;
                case IN, NIN -> LIST;
                case UNDER -> SCALAR_OR_LIST;
                default -> SCALAR;
            };
        }

        boolean accepts(boolean multiValued) {
            return this == SCALAR_OR_LIST || (this == LIST) == multiValued;
        }

        String describe(AttributeType type) {
            return switch (this) {
                case LIST -> " " + type + " 集合";
                case SCALAR_OR_LIST -> " " + type + " 单值或集合";
                default -> " " + type + " 单值";
            };
        }
    }
}
