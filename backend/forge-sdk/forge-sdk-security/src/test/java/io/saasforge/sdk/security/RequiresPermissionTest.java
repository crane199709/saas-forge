package io.saasforge.sdk.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.saasforge.sdk.core.MembershipStateKeys;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class RequiresPermissionTest {
    static final UUID TENANT_ID = UUID.fromString("019a0110-0000-7000-8000-000000000001");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    StringRedisTemplate template;

    @Test
    void membershipWithThePermissionGets200AndWithoutGets403() throws Exception {
        UUID allowed = publish("[\"order:read\"]");
        UUID denied = publish("[\"order:create\"]");

        mvc.perform(get("/orders").with(tenantJwt(allowed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("orders"));
        mvc.perform(get("/orders").with(tenantJwt(denied)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.forbidden"));
    }

    @Test
    void classLevelPermissionAppliesToAllMethodsAndMethodLevelOverridesIt() throws Exception {
        UUID reader = publish("[\"report:read\"]");

        mvc.perform(get("/reports").with(tenantJwt(reader))).andExpect(status().isOk());
        mvc.perform(get("/reports/export").with(tenantJwt(reader))).andExpect(status().isForbidden());
        mvc.perform(get("/reports/export").with(tenantJwt(publish("[\"report:export\"]"))))
                .andExpect(status().isOk());
    }

    @Test
    void endpointsWithoutTheAnnotationOnlyRequireAuthentication() throws Exception {
        mvc.perform(get("/context").with(tenantJwt(publish("[]")))).andExpect(status().isOk());
    }

    private UUID publish(String permissions) {
        UUID membershipId = UUID.randomUUID();
        template.opsForValue()
                .set(
                        MembershipStateKeys.grants(TENANT_ID, membershipId, 1),
                        "{\"roles\":[],\"permissions\":" + permissions + "}");
        return membershipId;
    }

    private static RequestPostProcessor tenantJwt(UUID membershipId) {
        return jwt().jwt(token -> token.subject("019a0110-0000-7000-8000-000000000002")
                .claim("tenant_id", TENANT_ID.toString())
                .claim("membership_id", membershipId.toString())
                .claim("ver", 1));
    }
}
