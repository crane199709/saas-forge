package io.saasforge.sdk.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ForgeExceptionTest {
    enum OrderErrorCode implements ErrorCode {
        NOT_FOUND("order.notFound", 404);

        private final String code;
        private final int httpStatus;

        OrderErrorCode(String code, int httpStatus) {
            this.code = code;
            this.httpStatus = httpStatus;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public int httpStatus() {
            return httpStatus;
        }
    }

    @Test
    void errorMessageIsGeneratedFromCodeAndArgs() {
        ForgeException e = new ForgeException(OrderErrorCode.NOT_FOUND, "o-1");

        assertThat(e.errorCode()).isEqualTo(OrderErrorCode.NOT_FOUND);
        assertThat(e.args()).containsExactly("o-1");
        assertThat(e.errorMessage()).isEqualTo("order.notFound [o-1]");
    }

    @Test
    void errorMessageWithoutArgsIsTheCode() {
        assertThat(new ForgeException(OrderErrorCode.NOT_FOUND).errorMessage()).isEqualTo("order.notFound");
    }

    @Test
    void explicitErrorMessageIsKeptAndArgsStillFillTheTip() {
        ForgeException e = ForgeException.withErrorMessage(
                OrderErrorCode.NOT_FOUND, "Order o-1 not found in current tenant", "o-1");

        assertThat(e.errorMessage()).isEqualTo("Order o-1 not found in current tenant");
        assertThat(e.getMessage()).isEqualTo("Order o-1 not found in current tenant");
        assertThat(e.args()).containsExactly("o-1");
    }
}
