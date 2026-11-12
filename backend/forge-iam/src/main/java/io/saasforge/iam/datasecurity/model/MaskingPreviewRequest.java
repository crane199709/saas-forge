package io.saasforge.iam.datasecurity.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MaskingPreviewRequest(
        @NotBlank @Size(max = 64) String ruleCode,
        @NotNull @Size(max = 4096) String value) {
    @Override
    public String toString() {
        return "MaskingPreviewRequest[ruleCode=" + ruleCode + ", value=[redacted]]";
    }
}
