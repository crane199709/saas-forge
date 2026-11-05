package io.saasforge.iam.mfa.model;

import java.util.UUID;

/** 待写入的一个 Recovery Code：应用侧生成的主键与码的哈希。 */
public record RecoveryCodeHash(UUID id, String codeHash) {
    @Override
    public String toString() {
        return "RecoveryCodeHash[id=" + id + "]";
    }
}
