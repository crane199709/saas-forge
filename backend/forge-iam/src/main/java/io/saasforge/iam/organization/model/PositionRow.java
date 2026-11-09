package io.saasforge.iam.organization.model;

import java.time.Instant;
import java.util.UUID;

/** 岗位查询投影。 */
public record PositionRow(UUID id, String name, Instant updatedAt, UUID updatedBy) {}
