package io.saasforge.sdk.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class FrameworkMessagesTest {
    static final Locale ZH_CN = Locale.forLanguageTag("zh-CN");

    @Test
    void everyFrameworkErrorCodeHasAZhCnTip() {
        for (FrameworkErrorCode code : FrameworkErrorCode.values()) {
            assertThat(FrameworkMessages.find(code.code(), ZH_CN))
                    .as(code.code())
                    .isPresent();
        }
    }

    @Test
    void frameworkCodesMatchSpecTable() {
        assertThat(FrameworkErrorCode.REQUEST_INVALID.code()).isEqualTo("request.invalid");
        assertThat(FrameworkErrorCode.REQUEST_INVALID.httpStatus()).isEqualTo(400);
        assertThat(FrameworkErrorCode.REQUEST_MALFORMED.httpStatus()).isEqualTo(400);
        assertThat(FrameworkErrorCode.REQUEST_NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(FrameworkErrorCode.REQUEST_METHOD_NOT_ALLOWED.code()).isEqualTo("request.methodNotAllowed");
        assertThat(FrameworkErrorCode.AUTH_UNAUTHENTICATED.httpStatus()).isEqualTo(401);
        assertThat(FrameworkErrorCode.AUTH_FORBIDDEN.httpStatus()).isEqualTo(403);
        assertThat(FrameworkErrorCode.SYSTEM_UNAVAILABLE.httpStatus()).isEqualTo(503);
        assertThat(FrameworkErrorCode.SYSTEM_ERROR.httpStatus()).isEqualTo(500);
    }

    @Test
    void tipIsLookedUpByCode() {
        assertThat(FrameworkMessages.find("request.notFound", ZH_CN)).contains("请求的资源不存在");
    }

    @Test
    void otherLanguagesFallBackToTheDefaultZhCnBundle() {
        assertThat(FrameworkMessages.find("request.notFound", Locale.ENGLISH)).contains("请求的资源不存在");
    }

    @Test
    void unknownCodeIsEmptyAndFallbackTipIsGeneric() {
        assertThat(FrameworkMessages.find("order.notFound", ZH_CN)).isEmpty();
        assertThat(FrameworkMessages.fallback(ZH_CN)).isEqualTo("操作失败，请稍后重试");
    }
}
