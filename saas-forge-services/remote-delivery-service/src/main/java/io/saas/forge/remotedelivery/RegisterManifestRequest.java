package io.saas.forge.remotedelivery;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterManifestRequest(
        @Pattern(regexp = "^[a-z][a-z0-9-]{1,62}$") @NotBlank String module,
        @Pattern(regexp = "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$") @NotBlank String version,
        @NotBlank @Size(max = 512) String source,
        @NotBlank @Size(max = 100) String uiVersion,
        @Pattern(regexp = "^[0-9a-f]{64}$") @NotBlank String entrySha256) {}
