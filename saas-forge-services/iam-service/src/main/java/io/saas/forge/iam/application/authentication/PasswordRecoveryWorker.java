package io.saas.forge.iam.application.authentication;

import org.springframework.scheduling.annotation.Scheduled;

public final class PasswordRecoveryWorker {
    private final PasswordRecoveryService service;

    public PasswordRecoveryWorker(PasswordRecoveryService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${security.password-recovery.finish-delay:PT10S}")
    public void finishCompleted() {
        service.finishCompleted();
    }
}
