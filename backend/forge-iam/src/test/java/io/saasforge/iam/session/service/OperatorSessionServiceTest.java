package io.saasforge.iam.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.saasforge.iam.lockout.service.LoginLockoutService;
import io.saasforge.iam.mfa.service.OperatorMfaService;
import io.saasforge.iam.operator.model.OperatorCredential;
import io.saasforge.iam.operator.service.OperatorService;
import io.saasforge.iam.security.config.IamProperties;
import io.saasforge.iam.security.service.TokenEndpointClient;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class OperatorSessionServiceTest {
    private final OperatorService operators = mock(OperatorService.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);

    @Test
    void passwordSignsInAsOperatorWithoutMfa() {
        UUID operatorId = UUID.randomUUID();
        when(operators.findCredential("operator"))
                .thenReturn(new OperatorCredential(operatorId, passwords.encode("correct")));
        var authentication = service().authenticate("operator", "correct");
        assertThat(authentication.getName()).isEqualTo(operatorId.toString());
        assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("OPERATOR");
    }

    @Test
    void wrongPasswordIsRejected() {
        when(operators.findCredential("operator"))
                .thenReturn(new OperatorCredential(UUID.randomUUID(), passwords.encode("correct")));
        assertThatThrownBy(() -> service().authenticate("operator", "wrong"))
                .isInstanceOfSatisfying(
                        ForgeException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(SessionErrorCode.AUTH_FAILED));
    }

    private OperatorSessionService service() {
        var properties = new IamProperties(
                "http://localhost:5174/",
                "signing.jwk",
                null,
                null,
                new IamProperties.Platform("http://localhost:5173/", "platform-signing.jwk", false));
        return new OperatorSessionService(
                operators,
                passwords,
                mock(TokenEndpointClient.class),
                mock(LoginAudit.class),
                mock(OperatorMfaService.class),
                properties,
                mock(LoginLockoutService.class));
    }
}
