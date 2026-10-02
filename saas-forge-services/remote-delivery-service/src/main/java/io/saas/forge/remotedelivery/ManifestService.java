package io.saas.forge.remotedelivery;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ManifestService {
    private final ManifestRepository manifests;
    private final ManifestPolicy policy;
    private final Clock clock;
    ManifestService(ManifestRepository manifests, ManifestPolicy policy, Clock clock) {
        this.manifests = manifests; this.policy = policy; this.clock = clock;
    }
    @Transactional
    public RemoteManifest register(UUID client, RegisterManifestRequest request) {
        policy.requireRegistration(client, request);
        manifests.lock("module:" + request.module());
        var existing = manifests.version(request.module(), request.version());
        if (existing.isPresent()) {
            var value = existing.get();
            if (!value.registeredBy().equals(client) || !value.source().equals(request.source())
                    || !value.uiVersion().equals(request.uiVersion()) || !value.entrySha256().equals(request.entrySha256()))
                throw new ManifestException(409, "MANIFEST_VERSION_CONFLICT");
            return value;
        }
        var at = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var result = manifests.insert(client, request, at);
        manifests.fact("REGISTER", client, null, result, at);
        return result;
    }
    @Transactional
    public RemoteManifest decide(UUID actor, UUID key, UUID id, String action) {
        if (id == null || id.version() != 7) throw new ManifestException(400, "MANIFEST_ID_INVALID");
        if (key == null || key.version() != 7) throw new ManifestException(400, "IDEMPOTENCY_KEY_INVALID");
        manifests.lock("operation:" + actor + ":" + key);
        var replay = manifests.replay(actor, key);
        if (replay.isPresent()) {
            if (!replay.get().action().equals(action) || !replay.get().manifestId().equals(id))
                throw new ManifestException(409, "IDEMPOTENCY_KEY_REUSED");
            return replay.get().result();
        }
        var current = manifests.find(id).orElseThrow(() -> new ManifestException(404, "MANIFEST_NOT_FOUND"));
        manifests.lock("module:" + current.module());
        if (action.equals("ENABLE")) {
            policy.requireSource(new RegisterManifestRequest(current.module(), current.version(), current.source(), current.uiVersion(), current.entrySha256()));
            if (manifests.hasEnabled(current.module())) throw new ManifestException(409, "MODULE_ALREADY_ENABLED");
        }
        var at = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var result = manifests.transition(id, action, actor, at);
        manifests.fact(action, actor, key, result, at);
        return result;
    }
    @Transactional(readOnly = true)
    public ManifestPage list(String cursor, int limit, String scope, boolean enabledOnly) {
        if (limit < 1 || limit > 100) throw new ManifestException(400, "INVALID_PAGE");
        UUID after = null;
        if (cursor != null) try {
            if (cursor.length() > 256) throw new IllegalArgumentException();
            var bytes = Base64.getUrlDecoder().decode(cursor);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(cursor)) throw new IllegalArgumentException();
            var fields = new String(bytes, StandardCharsets.UTF_8).split(":", -1);
            if (fields.length != 5 || !fields[0].equals("manifest-v1") || !fields[1].equals(scope)
                    || !fields[2].equals(Integer.toString(limit)) || !fields[3].equals(Boolean.toString(enabledOnly))) throw new IllegalArgumentException();
            after = UUID.fromString(fields[4]);
            if (after.version() != 7 || !after.toString().equals(fields[4])) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw new ManifestException(400, "INVALID_PAGE"); }
        var rows = manifests.list(after, limit + 1, enabledOnly);
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("manifest-v1:" + scope + ":" + limit + ":" + enabledOnly + ":" + items.get(items.size()-1).id()).getBytes(StandardCharsets.UTF_8)) : null;
        return new ManifestPage(items, next, more);
    }
    public record ManifestPage(List<RemoteManifest> items, String nextCursor, boolean hasMore) {}
}
