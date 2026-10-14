package io.saas.forge.remotedelivery;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;

public record RegisterManifestRequest(
        @Pattern(regexp = "^[a-z][a-z0-9-]{1,62}$") @NotBlank String module,
        @Pattern(regexp = "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$") @NotBlank String version,
        @NotBlank @Size(max = 512) String source,
        @NotBlank @Size(max = 100) String uiVersion,
        @Pattern(regexp = "^[0-9a-f]{64}$") @NotBlank String entrySha256,
        @Size(max = 10) List<@Pattern(regexp = "^[a-z][a-z0-9-]*:[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$") String> permissions) {
    public RegisterManifestRequest(String module, String version, String source, String uiVersion, String entrySha256) {
        this(module, version, source, uiVersion, entrySha256, List.of());
    }
    public RegisterManifestRequest {
        if (permissions == null) permissions = List.of();
        if (permissions.stream().anyMatch(Objects::isNull))
            throw new ManifestException(400, "MANIFEST_PERMISSION_NOT_ALLOWED");
        permissions = List.copyOf(permissions);
    }
}
