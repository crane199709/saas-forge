package io.saasforge.iam.todo.model;

import java.util.Set;
import java.util.UUID;

/** 本次请求从当前数据库计算的资格；subApps 为 null 表示旧租户尚无套餐限制。 */
public record TodoVisibility(UUID tenantId, UUID membershipId, Set<String> permissions, Set<String> subApps) {}
