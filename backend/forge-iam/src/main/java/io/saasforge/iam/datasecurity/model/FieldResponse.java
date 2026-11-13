package io.saasforge.iam.datasecurity.model;

public record FieldResponse(
        String service,
        String path,
        String grade,
        String defaultRule,
        String overrideRule,
        String effectiveRule,
        String status,
        boolean exportAllowed) {}
