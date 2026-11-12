package io.saasforge.iam.datasecurity.controller;

import io.saasforge.sdk.security.MaskingRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

final class DataSecurityRequests {
    private DataSecurityRequests() {}

    record CreateRule(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
            String code,

            @NotBlank @Size(max = 120) String name,
            @NotNull @Valid MaskingRule rule) {}

    record UpdateRule(
            @NotBlank @Size(max = 120) String name,
            @NotNull @Valid MaskingRule rule) {}

    record RuleSelection(@NotBlank @Size(max = 64) String ruleCode) {}

    record GradeDefault(@NotBlank @Size(max = 64) String defaultRule) {}

    record Preview(
            @NotBlank @Size(max = 64) String ruleCode,
            @NotNull @Size(max = 4096) String value) {
        @Override
        public String toString() {
            return "Preview[ruleCode=" + ruleCode + ", value=[redacted]]";
        }
    }
}
