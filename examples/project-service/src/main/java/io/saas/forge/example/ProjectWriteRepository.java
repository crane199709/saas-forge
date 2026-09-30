package io.saas.forge.example;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class ProjectWriteRepository {
    private final ProjectWriteMapper mapper;
    ProjectWriteRepository(ProjectWriteMapper mapper) { this.mapper = mapper; }

    record WriteKey(UUID identity, UUID key, UUID tenant, String fingerprint) {}
    record Existing(String fingerprint, ProjectWriteResult result) {}

    void lock(WriteKey key) {
        long lock = ByteBuffer.wrap(digest(key.identity() + ":" + key.key())).getLong();
        // 事务锁无持久处理中残留；跨进程并发同键立即失败，回滚/断连自动释放。
        if (!mapper.lock(lock)) {
            throw new ProjectException(409, "IDEMPOTENCY_REQUEST_IN_PROGRESS", "The request is still in progress.");
        }
    }

    void expire(WriteKey key) { mapper.expire(key); }

    boolean claim(WriteKey key) { return mapper.claim(key) == 1; }

    Optional<Existing> find(WriteKey key) {
        return Optional.ofNullable(mapper.find(key)).map(row -> new Existing(row.fingerprint(),
                row.status() == null ? null : new ProjectWriteResult(row.status(), row.body(), row.location())));
    }

    void complete(WriteKey key, ProjectWriteResult result) {
        if (mapper.complete(key, result) != 1) throw new IllegalStateException("幂等完成记录丢失");
    }

    static String fingerprint(String normalizedBody) {
        return HexFormat.of().formatHex(digest("POST\n/api/v1/projects\n" + normalizedBody));
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
