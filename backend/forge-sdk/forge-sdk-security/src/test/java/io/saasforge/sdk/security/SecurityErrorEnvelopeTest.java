package io.saasforge.sdk.security;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityErrorEnvelopeTest {
    static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    static final String TRACEPARENT = "00-" + TRACE_ID + "-b7ad6b7169203331-01";

    @Autowired
    MockMvc mvc;

    @Test
    void missingTokenIs401Envelope() throws Exception {
        mvc.perform(get("/context").header("traceparent", TRACEPARENT))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("auth.unauthenticated"))
                .andExpect(jsonPath("$.httpStatus").value(401))
                .andExpect(jsonPath("$.userTip").value("登录已失效，请重新登录"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void invalidBearerTokenIs401EnvelopeAndKeepsBearerChallenge() throws Exception {
        mvc.perform(get("/context").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                .andExpect(jsonPath("$.code").value("auth.unauthenticated"))
                .andExpect(jsonPath("$.userTip").value("登录已失效，请重新登录"));
    }

    // forge-sdk-web 的兜底 500 处理不能吞掉方法安全的拒绝
    @Test
    void methodSecurityDenialIs403EnvelopeEvenWithWebSdkCatchAll() throws Exception {
        mvc.perform(get("/admin").with(tenantJwt()).header("traceparent", TRACEPARENT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.forbidden"))
                .andExpect(jsonPath("$.httpStatus").value(403))
                .andExpect(jsonPath("$.userTip").value("没有权限执行该操作"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void authenticatedRequestStillReachesController() throws Exception {
        mvc.perform(get("/context").with(tenantJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("019a0110-0000-7000-8000-000000000001"));
    }

    private static RequestPostProcessor tenantJwt() {
        return jwt().jwt(token -> token.subject("019a0110-0000-7000-8000-000000000002")
                .claim("tenant_id", "019a0110-0000-7000-8000-000000000001")
                .claim("membership_id", "019a0110-0000-7000-8000-000000000003")
                .claim("ver", 1));
    }
}
