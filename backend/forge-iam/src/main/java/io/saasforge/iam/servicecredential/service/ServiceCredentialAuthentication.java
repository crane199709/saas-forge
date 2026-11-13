package io.saasforge.iam.servicecredential.service;

import io.saasforge.iam.servicecredential.config.ManifestProperties;
import io.saasforge.iam.servicecredential.mapper.ServiceCredentialMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 每请求读取权威数据库；禁止为认证结果或凭证状态增加缓存。 */
@Service
public class ServiceCredentialAuthentication {
    private static final Logger log = LoggerFactory.getLogger(ServiceCredentialAuthentication.class);
    private final ServiceCredentialMapper mapper;
    private final ManifestProperties properties;
    private final ServiceCredentialFailureAudit failures;

    public ServiceCredentialAuthentication(
            ServiceCredentialMapper mapper, ManifestProperties properties, ServiceCredentialFailureAudit failures) {
        this.mapper = mapper;
        this.properties = properties;
        this.failures = failures;
    }

    @Transactional
    public String authenticate(String token, String source) {
        String digest = ServiceCredentialDigest.of(token);
        // 查询包括 disabled 行：它一旦落库就由数据库决定，静态配置不能使已吊销凭证复活。
        var row = mapper.findByDigest(digest);
        if (row != null) {
            if (ServiceCredentialDigest.matches(row.digest(), digest)
                    && "active".equals(row.status())
                    && mapper.touch(row.id()) == 1) {
                return row.service();
            }
            failures.record(row.service(), source, digest.substring(0, 8));
            return null;
        }
        String service = null;
        for (var entry : properties.tokens().entrySet()) {
            if (ServiceCredentialDigest.matches(ServiceCredentialDigest.of(entry.getValue()), digest)) {
                service = entry.getKey();
            }
        }
        if (service != null) {
            log.warn("静态凭证已弃用：service={}", service);
        }
        if (service == null) {
            failures.record("unknown", source, digest.substring(0, 8));
        }
        return service;
    }
}
