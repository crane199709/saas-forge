package io.saasforge.sdk.abac;

import java.util.List;
import java.util.Map;

/** 测试共用的属性目录与列映射，对应 {@code abac/documents.sql}。 */
final class TestCatalog {
    static final AttributeCatalog CATALOG = AttributeCatalog.of(List.of(
            AttributeDefinition.single("subject.membershipId", AttributeType.UUID),
            AttributeDefinition.multi("subject.departmentPaths", AttributeType.LTREE),
            AttributeDefinition.single("subject.position", AttributeType.STRING),
            AttributeDefinition.multi("subject.positions", AttributeType.STRING),
            AttributeDefinition.single("subject.level", AttributeType.NUMBER),
            AttributeDefinition.single("env.region", AttributeType.STRING),
            AttributeDefinition.single("resource.ownerId", AttributeType.UUID),
            AttributeDefinition.single("resource.departmentPath", AttributeType.LTREE),
            AttributeDefinition.single("resource.title", AttributeType.STRING),
            AttributeDefinition.single("resource.amount", AttributeType.NUMBER),
            AttributeDefinition.single("resource.archived", AttributeType.BOOLEAN),
            AttributeDefinition.multi("resource.tags", AttributeType.STRING)));

    static final Map<String, String> COLUMNS = Map.of(
            "resource.ownerId", "d.owner_id",
            "resource.departmentPath", "d.department_path",
            "resource.title", "d.title",
            "resource.amount", "d.amount",
            "resource.archived", "d.archived",
            "resource.tags", "d.tags");

    private TestCatalog() {}

    static Expression parse(String json) {
        return PolicyParser.parse(json, CATALOG);
    }
}
