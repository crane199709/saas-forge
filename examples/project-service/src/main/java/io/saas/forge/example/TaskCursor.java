package io.saas.forge.example;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** 游标固定按 ID 升序，并绑定资源、筛选和有效期；不承担认证或授权。 */
final class TaskCursor {
    private final Clock clock;

    TaskCursor(Clock clock) { this.clock = clock; }

    String encode(String scope, UUID id) {
        String value = scope + "\n" + clock.instant().plus(Duration.ofHours(24)) + "\n" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    UUID decode(String scope, String cursor) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 2048) throw new IllegalArgumentException();
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String prefix = scope + "\n";
            if (!value.startsWith(prefix)) throw new IllegalArgumentException();
            String[] parts = value.substring(prefix.length()).split("\n", -1);
            if (parts.length != 2 || !clock.instant().isBefore(Instant.parse(parts[0]))) {
                throw new IllegalArgumentException();
            }
            UUID id = UUID.fromString(parts[1]);
            if (id.version() != 7 || !id.toString().equals(parts[1])) throw new IllegalArgumentException();
            return id;
        } catch (RuntimeException exception) {
            throw new ProjectException(400, "VALIDATION_FAILED", "Invalid, mismatched or expired cursor.");
        }
    }
}
