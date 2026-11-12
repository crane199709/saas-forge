package io.saasforge.iam.servicecredential.service;

import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 仅失败审计允许进程内限流；此状态不得参与凭证认证或吊销判断。 */
@Service
public class ServiceCredentialFailureAudit {
    private static final long WINDOW = Duration.ofMinutes(1).toNanos();
    private static final int LIMIT = 20;
    private final AuditRecorder audits;
    private final Set<Source> emitted = new HashSet<>();
    private long windowStarted = System.nanoTime();

    ServiceCredentialFailureAudit(AuditRecorder audits) {
        this.audits = audits;
    }

    public void record(String service, String source, String fingerprint) {
        if (!admit(new Source(service, source))) return;
        audits.record(AuditEvent.builder(AuditRealm.PLATFORM, AuditCategory.LOGIN, "serviceCredential.authenticate")
                .detail("service", service)
                .detail("source", source)
                .detail("fingerprint", fingerprint)
                .failure("auth.unauthenticated")
                .build());
    }

    private synchronized boolean admit(Source source) {
        long now = System.nanoTime();
        if (now - windowStarted >= WINDOW) {
            emitted.clear();
            windowStarted = now;
        }
        // 每实例每分钟最多 20 条；去重键不含请求令牌，随机换令牌无法绕过限流或撑大内存。
        return emitted.size() < LIMIT && emitted.add(source);
    }

    private record Source(String service, String source) {}
}
