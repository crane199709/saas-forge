package io.saas.forge.iam.application.authorization;

import org.springframework.scheduling.annotation.Scheduled;

public final class PlatformAdminDeliveryWorker {
    private final PlatformAdminGrantService grants;

    public PlatformAdminDeliveryWorker(PlatformAdminGrantService grants) {
        this.grants = grants;
    }

    @Scheduled(fixedDelayString = "${saas.forge.iam.platform-admin-delivery.recovery-delay:PT10S}")
    public void recoverPending() {
        grants.recoverPending();
    }
}
