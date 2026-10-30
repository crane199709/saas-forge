package io.saasforge.iam.organization.service;

import io.saasforge.iam.organization.model.DepartmentRow;
import io.saasforge.iam.role.model.ValueOption;
import io.saasforge.iam.role.service.OrganizationConditionSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OrganizationConditionService implements OrganizationConditionSource {
    private final ExtendedAttributeService attributes;
    private final DepartmentService departments;
    private final PositionService positions;

    OrganizationConditionService(
            ExtendedAttributeService attributes, DepartmentService departments, PositionService positions) {
        this.attributes = attributes;
        this.departments = departments;
        this.positions = positions;
    }

    @Override
    public List<Attribute> attributes(UUID tenantId) {
        return attributes.definitions(tenantId).stream()
                .map(row -> new Attribute(row.code(), row.name(), row.type()))
                .toList();
    }

    @Override
    public List<ValueOption> departments(UUID tenantId) {
        return departmentOptions(departments.all(tenantId));
    }

    @Override
    public List<ValueOption> positions(UUID tenantId) {
        return positions.all(tenantId).stream()
                .map(row -> new ValueOption(row.id().toString(), row.name()))
                .toList();
    }
    // 部门按路径排序，上级总在下级之前；上级已删除的部门以自身名称开头。
    private static List<ValueOption> departmentOptions(List<DepartmentRow> rows) {
        Map<UUID, String> labels = new HashMap<>();
        return rows.stream()
                .map(row -> {
                    String parent = row.parentId() == null ? null : labels.get(row.parentId());
                    String label = parent == null ? row.name() : parent + " / " + row.name();
                    labels.put(row.id(), label);
                    return new ValueOption(row.path(), label);
                })
                .toList();
    }
}
