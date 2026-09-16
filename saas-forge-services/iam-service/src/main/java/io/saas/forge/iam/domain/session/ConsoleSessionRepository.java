package io.saas.forge.iam.domain.session;

import io.saas.forge.iam.domain.shared.Sha256Digest;
import java.util.Optional;
import java.util.UUID;

public interface ConsoleSessionRepository {
    ConsoleSlot create(Sha256Digest locator);
    /** 必须在调用方事务中持有 Slot 锁直到本次会话操作提交。 */
    Optional<ConsoleSlot> lock(Sha256Digest locator);
    void save(ConsoleSlot slot);
    void beginLegacyRetirement();
    java.util.List<UUID> pendingLegacyFamilies(int limit);
    void confirmLegacyRetirement(UUID familyId);
    boolean legacyRetirementComplete();
    boolean isConsoleFamily(UUID familyId);
    Optional<ConsoleOperation> operation(UUID slotId, UUID key);
    void begin(ConsoleOperation operation);
    void complete(UUID slotId, UUID key, long revision);
    void completeLogout(UUID slotId, UUID familyId, long revision);
}
