package io.saasforge.iam.datasecurity.service;

import io.saasforge.iam.datasecurity.mapper.DataSecurityMapper;
import io.saasforge.iam.permission.service.ManifestPolicy;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.List;
import org.springframework.stereotype.Service;

/** 只访问规则存储，不注入清单服务；permission 功能包不引用本功能包。 */
@Service
class MaskingManifestPolicy implements ManifestPolicy {
    private final DataSecurityMapper mapper;
    private final AuditRecorder audits;

    MaskingManifestPolicy(DataSecurityMapper mapper, AuditRecorder audits) {
        this.mapper = mapper;
        this.audits = audits;
    }

    @Override
    public void validate(ResourceManifest manifest) {
        // 空声明也锁定：新版本可能移除旧引用，须与平台删除规则采用同一串行边界。
        mapper.lock();
        for (var field : manifest.sensitiveFields()) {
            if (field.defaultRule() != null && mapper.rule(field.defaultRule()) == null) {
                throw new ForgeException(DataSecurityErrorCode.INVALID_REFERENCE);
            }
        }
    }

    @Override
    public void registered(ResourceManifest previous, ResourceManifest current) {
        var before = previous == null ? List.of() : previous.sensitiveFields();
        if (!before.equals(current.sensitiveFields())) {
            audits.record(AuditEvent.builder(AuditRealm.PLATFORM, AuditCategory.OPERATION, "sensitive-field.register")
                    .resource("sensitiveField", current.service())
                    .detail("manifestVersion", current.version())
                    .detail("fieldCount", current.sensitiveFields().size())
                    .build());
        }
    }
}
