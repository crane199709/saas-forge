package io.saasforge.sdk.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** 业务代码不参与：日志自动带上 traceId、Token 中的租户与 Membership。 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LogContextTest {
    static final String TENANT_ID = "019a0110-0000-7000-8000-000000000001";
    static final String MEMBERSHIP_ID = "019a0110-0000-7000-8000-000000000003";
    static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    @Autowired
    MockMvc mvc;

    // 请求结束后清除，避免容器线程复用时串到其他请求
    @Test
    void tenantAndMembershipAreInMdcDuringRequestOnly() throws Exception {
        mvc.perform(get("/log-context").with(tenantJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenant_id").value(TENANT_ID))
                .andExpect(jsonPath("$.data.membership_id").value(MEMBERSHIP_ID));
        assertThat(MDC.get("tenant_id")).isNull();
        assertThat(MDC.get("membership_id")).isNull();
    }

    @Test
    void logLineCarriesTraceTenantAndMembership(CapturedOutput output) throws Exception {
        mvc.perform(get("/log-context")
                        .with(tenantJwt())
                        .header("traceparent", "00-" + TRACE_ID + "-b7ad6b7169203331-01"))
                .andExpect(status().isOk());
        assertThat(output.getAll().lines().filter(line -> line.contains("probe log line")))
                .singleElement()
                .asString()
                .contains(TRACE_ID, TENANT_ID, MEMBERSHIP_ID);
    }

    private static RequestPostProcessor tenantJwt() {
        return jwt().jwt(token -> token.subject("019a0110-0000-7000-8000-000000000002")
                .claim("tenant_id", TENANT_ID)
                .claim("membership_id", MEMBERSHIP_ID)
                .claim("ver", 1));
    }
}
