package io.saasforge.iam.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.saasforge.iam.operator.model.OperatorCredential;
import io.saasforge.iam.operator.service.OperatorService;
import io.saasforge.iam.security.service.TokenEndpointClient;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class OperatorSessionServiceTest {
    private final OperatorService operators = mock(OperatorService.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);

    @Test
    void requiredMfaRejectsEveryLoginBeforeCheckingThePassword() {
        var service = service(true);
        assertThatThrownBy(() -> service.authenticate("operator", "correct"))
                .isInstanceOfSatisfying(
                        ForgeException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(SessionErrorCode.MFA_REQUIRED));
        verifyNoInteractions(operators);
    }

    @Test
    void optionalMfaSignsInWithPasswordAsOperator() {
        UUID operatorId = UUID.randomUUID();
        when(operators.findCredential("operator"))
                .thenReturn(new OperatorCredential(operatorId, passwords.encode("correct")));
        var authentication = service(false).authenticate("operator", "correct");
        assertThat(authentication.getName()).isEqualTo(operatorId.toString());
        assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("OPERATOR");
    }

    private OperatorSessionService service(boolean mfaRequired) {
        return new OperatorSessionService(
                operators, passwords, mock(TokenEndpointClient.class), "http://localhost:5173/", mfaRequired);
    }
}
