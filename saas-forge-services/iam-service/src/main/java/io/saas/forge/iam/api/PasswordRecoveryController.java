package io.saas.forge.iam.api;

import io.saas.forge.iam.application.authentication.PasswordRecoveryService;
import io.saas.forge.iam.console.contract.api.PasswordRecoveryApi;
import io.saas.forge.iam.console.contract.model.PasswordRecoveryRedeemRequest;
import io.saas.forge.iam.console.contract.model.PasswordRecoveryRequest;
import io.saas.forge.iam.console.contract.model.PasswordRecoveryRequestResult;
import io.saas.forge.iam.console.contract.model.PasswordRecoveryResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "security.browser.console-enabled", havingValue = "true")
public class PasswordRecoveryController implements PasswordRecoveryApi {
    private final PasswordRecoveryService service;

    public PasswordRecoveryController(PasswordRecoveryService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<PasswordRecoveryRequestResult> requestPasswordRecovery(
            String csrf, PasswordRecoveryRequest request) {
        service.request(request.getEmail());
        return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new PasswordRecoveryRequestResult(PasswordRecoveryRequestResult.StatusEnum.ACCEPTED));
    }

    @Override
    public ResponseEntity<PasswordRecoveryResult> redeemPasswordRecovery(
            String csrf, PasswordRecoveryRedeemRequest request) {
        var outcome = service.redeem(request.getToken(), request.getNewPassword());
        if (outcome == PasswordRecoveryService.Status.PENDING) {
            return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.RETRY_AFTER, "2")
                    .body(new PasswordRecoveryResult(PasswordRecoveryResult.StatusEnum.PENDING));
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new PasswordRecoveryResult(PasswordRecoveryResult.StatusEnum.COMPLETED));
    }
}
