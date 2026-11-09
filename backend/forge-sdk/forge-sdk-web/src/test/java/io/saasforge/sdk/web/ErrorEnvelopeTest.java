package io.saasforge.sdk.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ErrorEnvelopeTest {
    static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    static final String TRACEPARENT = "00-" + TRACE_ID + "-b7ad6b7169203331-01";

    static Locale jvmDefault;

    @Autowired
    MockMvc mvc;

    // JVM 默认语言设为英文，才能证明缺省 Accept-Language 时用的是 zh-CN 而不是部署机器的 locale
    @BeforeAll
    static void useEnglishJvmLocale() {
        jvmDefault = Locale.getDefault();
        Locale.setDefault(Locale.US);
    }

    @AfterAll
    static void restoreJvmLocale() {
        Locale.setDefault(jvmDefault);
    }

    @Test
    void forgeExceptionUsesErrorCodeAndServiceMessages() throws Exception {
        mvc.perform(get("/orders/missing").header("traceparent", TRACEPARENT))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("order.notFound"))
                .andExpect(jsonPath("$.httpStatus").value(404))
                .andExpect(jsonPath("$.errorMessage").value("Order missing not found in current tenant"))
                .andExpect(jsonPath("$.userTip").value("订单 missing 不存在或已被删除"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void restoreConflictUsesSharedCodeAndActionableTip() throws Exception {
        mvc.perform(post("/orders/conflict/restore"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("resource.restoreConflict"))
                .andExpect(jsonPath("$.httpStatus").value(409))
                .andExpect(jsonPath("$.userTip").value("名称或编码已被占用，请先改名或处理冲突记录后再恢复"));
    }

    @Test
    void restoreBlockedUsesSharedCodeAndActionableTip() throws Exception {
        mvc.perform(post("/orders/blocked/restore"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("resource.restoreBlocked"))
                .andExpect(jsonPath("$.httpStatus").value(409))
                .andExpect(jsonPath("$.userTip").value("关联资源尚未恢复，请先恢复关联资源后重试"));
    }

    @ParameterizedTest
    @CsvSource({
        "conflict, The name or code is already in use. Rename it or resolve the conflicting record before restoring.",
        "blocked, A related resource is still deleted. Restore it first and try again."
    })
    void restoreErrorsUseRequestedEnglishLanguage(String id, String tip) throws Exception {
        mvc.perform(post("/orders/" + id + "/restore").header("Accept-Language", "en"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.userTip").value(tip));
    }

    @Test
    void codeWithoutMessageFallsBackToGenericTip() throws Exception {
        mvc.perform(get("/orders/untranslated"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("order.untranslated"))
                .andExpect(jsonPath("$.userTip").value("操作失败，请稍后重试"));
    }

    @Test
    void serverSideForgeExceptionHidesDetails() throws Exception {
        mvc.perform(get("/orders/broken"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("order.storageBroken"))
                .andExpect(jsonPath("$.errorMessage").value("Internal error"));
    }

    @Test
    void unknownExceptionIs500WithoutExceptionTextAndIsLoggedWithTraceId(CapturedOutput output) throws Exception {
        mvc.perform(get("/orders/boom").header("traceparent", TRACEPARENT))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("system.error"))
                .andExpect(jsonPath("$.httpStatus").value(500))
                .andExpect(jsonPath("$.errorMessage").value("Internal error"))
                .andExpect(jsonPath("$.userTip").value("系统繁忙，请稍后重试"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));

        assertThat(output.getAll()).contains("database password=secret leaked").contains(TRACE_ID);
    }

    @Test
    void unavailableResourceIs503WithoutConnectionDetails() throws Exception {
        mvc.perform(get("/orders/redis-down"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("system.unavailable"))
                .andExpect(jsonPath("$.httpStatus").value(503))
                .andExpect(jsonPath("$.errorMessage").value("Internal error"))
                .andExpect(jsonPath("$.userTip").value("服务暂时不可用，请稍后重试"))
                .andExpect(content().string(not(containsString("10.0.0.1"))));
    }

    @Test
    void beanValidationFailureIs400WithFieldErrors() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" \",\"amount\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("request.invalid"))
                .andExpect(jsonPath("$.httpStatus").value(400))
                .andExpect(jsonPath("$.errorMessage").value("2 field(s) failed validation"))
                .andExpect(jsonPath("$.userTip").value("请检查填写的内容"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field == 'amount')].code").value("validation.positive"))
                .andExpect(jsonPath("$.errors[?(@.field == 'amount')].userTip").value("金额必须大于 0"))
                .andExpect(jsonPath("$.errors[?(@.field == 'title')].code").value("validation.notBlank"))
                .andExpect(jsonPath("$.errors[?(@.field == 'title')].userTip").value("不能为空"));
    }

    @Test
    void requestLanguageIsUsedWhenGiven() throws Exception {
        mvc.perform(post("/orders")
                        .header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\" \",\"amount\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].userTip").value("must not be blank"));
    }

    @Test
    void malformedJsonIs400Malformed() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{\"title\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("request.malformed"))
                .andExpect(jsonPath("$.userTip").value("请求格式不正确"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void parameterTypeMismatchIs400Malformed() throws Exception {
        mvc.perform(get("/orders/abc/amount"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("request.malformed"));
    }

    @Test
    void unknownRouteIs404NotFound() throws Exception {
        mvc.perform(get("/no-such-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("request.notFound"))
                .andExpect(jsonPath("$.httpStatus").value(404))
                .andExpect(jsonPath("$.userTip").value("请求的资源不存在"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void unsupportedMethodIs405() throws Exception {
        mvc.perform(put("/orders/o-1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.code").value("request.methodNotAllowed"))
                .andExpect(jsonPath("$.httpStatus").value(405))
                .andExpect(jsonPath("$.userTip").value("不支持该请求方式"));
    }

    @Test
    void otherFrameworkClientErrorsKeepTheirStatus() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("request.malformed"))
                .andExpect(jsonPath("$.httpStatus").value(415));
    }
}
