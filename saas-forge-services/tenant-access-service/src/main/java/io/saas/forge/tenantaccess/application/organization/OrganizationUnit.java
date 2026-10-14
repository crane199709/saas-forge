package io.saas.forge.tenantaccess.application.organization;

import java.util.UUID;

public record OrganizationUnit(UUID id, UUID parentId, String name, int memberCount) {}
