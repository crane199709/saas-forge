package io.saas.forge.iam.domain.session;

import java.util.UUID;

/** 非敏感持久重试记录；不保存密码、Refresh Token 或 Access Token 响应。 */
public record ConsoleOperation(UUID slotId, UUID key, UUID familyId, Kind kind,
                               String fingerprint, Long resultRevision) {
    public enum Kind { LOGOUT, SELECT_CONTEXT, PASSWORD_CHANGE }
    public boolean completed() { return resultRevision != null; }
}
