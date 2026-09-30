package io.saas.forge.example;

import java.util.UUID;

/** 对外资源表示；Tenant 归属来自 Starter，不作为可写或可选取的资源字段。 */
public record ProjectResult(UUID id, String name, String description, long version, String createdAt, String updatedAt) {}
