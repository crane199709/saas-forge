package io.saas.forge.iam.application.authorization;

import org.springframework.scheduling.annotation.Scheduled;

public final class PlatformAdminRevokeWorker {
    private final PlatformAdminRevokeService service;

    public PlatformAdminRevokeWorker(PlatformAdminRevokeService service) { this.service = service; }

    @Scheduled(fixedDelayString = "${saas.forge.iam.platform-admin.revoke-recovery-delay:PT5S}")
    public void recover() { service.recoverPending(); }
}
