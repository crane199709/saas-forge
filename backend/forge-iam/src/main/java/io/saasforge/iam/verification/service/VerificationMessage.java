package io.saasforge.iam.verification.service;

import java.time.Duration;

/** 交给发送实现的验证码内容，发送实现据用途选择模板。 */
public record VerificationMessage(VerificationPurpose purpose, String code, Duration ttl) implements ContactMessage {
    @Override
    public String toString() {
        return "VerificationMessage[purpose=" + purpose + ", ttl=" + ttl + "]";
    }
}
