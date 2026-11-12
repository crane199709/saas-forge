package io.saasforge.iam.datasecurity.model;

import io.saasforge.sdk.security.MaskingRule;

public record RuleResponse(String code, String name, MaskingRule rule) {}
