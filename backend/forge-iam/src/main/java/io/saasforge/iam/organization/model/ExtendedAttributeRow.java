package io.saasforge.iam.organization.model;

import java.util.UUID;

/** 扩展属性定义查询投影；{@code type} 取值 {@code string | number | boolean}。 */
public record ExtendedAttributeRow(UUID id, String code, String name, String type, boolean required) {}
