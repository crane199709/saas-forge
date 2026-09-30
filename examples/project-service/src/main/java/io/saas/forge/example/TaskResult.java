package io.saas.forge.example;

import java.util.UUID;

public record TaskResult(UUID id, UUID projectId, String title, String description, Status status,
                         long version, String createdAt, String updatedAt) {
    public enum Status { TODO, IN_PROGRESS, DONE }
}
