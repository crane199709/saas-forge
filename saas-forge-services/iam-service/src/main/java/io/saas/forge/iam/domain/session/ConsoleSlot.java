package io.saas.forge.iam.domain.session;

import java.util.UUID;

/** 定位值只在 Repository 使用摘要；Slot 自身不授予 Identity 或业务访问权。 */
public record ConsoleSlot(UUID id, long revision, UUID familyId, Transition transition) {
    public enum Transition { NONE, SWITCH_PENDING, CONTEXT_REFRESH_REQUIRED, ENDING }

    public ConsoleSlot attach(UUID family) {
        if (familyId != null || family == null) throw new IllegalStateException("Slot 已有关联会话");
        return new ConsoleSlot(id, Math.incrementExact(revision), family, Transition.NONE);
    }

    public ConsoleSlot transitionTo(Transition next) {
        if (familyId == null) throw new IllegalStateException("Slot 没有关联会话");
        return new ConsoleSlot(id, Math.incrementExact(revision), familyId, next);
    }

    public ConsoleSlot finishRefresh() {
        return new ConsoleSlot(id, revision, familyId, Transition.NONE);
    }

    public ConsoleSlot end() {
        return new ConsoleSlot(id, Math.incrementExact(revision), null, Transition.NONE);
    }
}
