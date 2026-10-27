package io.saasforge.iam.organization.model;

import java.util.UUID;

/** 成员担任的一个岗位。 */
public record PositionAssignmentRow(UUID membershipId, UUID positionId) {}
