package io.saasforge.iam.role.service;

import io.saasforge.iam.role.model.ValueOption;
import java.util.List;
import java.util.UUID;

/** 角色条件编辑所需的组织数据；由组织功能实现，避免角色与组织相互依赖。 */
public interface OrganizationConditionSource {
    List<Attribute> attributes(UUID tenantId);

    List<ValueOption> departments(UUID tenantId);

    List<ValueOption> positions(UUID tenantId);

    record Attribute(String code, String name, String type) {}
}
