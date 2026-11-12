package io.saasforge.iam.servicecredential.service;

import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.servicecredential.mapper.ServiceCredentialMapper;
import io.saasforge.iam.servicecredential.model.CreatedServiceCredentialResponse;
import io.saasforge.iam.servicecredential.model.ServiceCredentialResponse;
import io.saasforge.iam.servicecredential.model.ServiceCredentialRow;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServiceCredentialService {
    private final ServiceCredentialMapper mapper;
    private final AuditRecorder audits;
    private final PermissionService permissions;
    private final SecureRandom random = new SecureRandom();

    ServiceCredentialService(ServiceCredentialMapper mapper, AuditRecorder audits, PermissionService permissions) {
        this.mapper = mapper;
        this.audits = audits;
        this.permissions = permissions;
    }
    /** 登记服务无需先上报清单；操作者必须来自经过验证的 Operator 身份。 */
    @Transactional
    public CreatedServiceCredentialResponse create(String service, UUID actor) {
        Objects.requireNonNull(actor, "actor");
        if (service == null || !service.matches("[a-z][a-z0-9-]{0,99}"))
            throw new ForgeException(ServiceCredentialErrorCode.INVALID_SERVICE);
        var created = issue(service, actor);
        audit("serviceCredential.create", created.credential(), actor);
        return created;
    }

    public boolean manifestReported(String service) {
        return permissions.reportedServices().contains(service);
    }

    public List<ServiceCredentialResponse> list(String service) {
        return mapper.list(service).stream().map(ServiceCredentialRow::response).toList();
    }

    @Transactional
    public void disable(UUID id, UUID actor) {
        Objects.requireNonNull(actor, "actor");
        var credential = require(id);
        if (mapper.disable(id, actor) != 0) audit("serviceCredential.disable", credential.response(), actor);
    }
    /** 紧急轮换：同一事务创建新凭证并立即停用旧凭证；平滑部署请使用 create 后显式 disable。 */
    @Transactional
    public CreatedServiceCredentialResponse rotate(UUID id, UUID actor) {
        Objects.requireNonNull(actor, "actor");
        var previous = require(id);
        if (mapper.disable(id, actor) != 1) throw new ForgeException(ServiceCredentialErrorCode.NOT_ACTIVE);
        var created = issue(previous.service(), actor);
        audit("serviceCredential.rotate", created.credential(), actor);
        return created;
    }

    private CreatedServiceCredentialResponse issue(String service, UUID actor) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var row = mapper.insert(UuidV7.generate(), service, ServiceCredentialDigest.of(token), actor);
        return new CreatedServiceCredentialResponse(row.response(), token);
    }

    private ServiceCredentialRow require(UUID id) {
        var row = mapper.find(id);
        if (row == null) throw new ForgeException(ServiceCredentialErrorCode.NOT_FOUND);
        return row;
    }

    private void audit(String action, ServiceCredentialResponse row, UUID actor) {
        audits.record(AuditEvent.builder(AuditRealm.PLATFORM, AuditCategory.OPERATION, action)
                .actor(actor)
                .resource("serviceCredential", row.id())
                .detail("service", row.service())
                .detail("fingerprint", row.fingerprint())
                .build());
    }
}
