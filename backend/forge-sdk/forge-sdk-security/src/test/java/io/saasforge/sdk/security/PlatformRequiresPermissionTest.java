package io.saasforge.sdk.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.saasforge.sdk.core.OperatorStateKeys;
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

/** Platform Realm 服务上的 {@code @RequiresPermission} 按当前 Operator 当前版本的平台角色明细判断（工单 22）。 */
@SpringBootTest(
        properties = {
            "forge.security.realm=platform",
            "forge.security.platform.issuer=http://localhost:8080/iam/platform",
            "forge.security.platform.jwk-set-uri=http://localhost:8101/platform/oauth2/jwks"
        })
@AutoConfigureMockMvc
class PlatformRequiresPermissionTest {
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    StringRedisTemplate template;

    @Test
    void operatorWithThePermissionGets200AndWithoutGets403() throws Exception {
        UUID allowed = publish(1, "[\"order:read\"]");
        UUID denied = publish(1, "[\"order:create\"]");

        mvc.perform(get("/orders").with(operatorJwt(allowed, 1))).andExpect(status().isOk());
        mvc.perform(get("/orders").with(operatorJwt(denied, 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.forbidden"));
    }

    @Test
    void grantsAreReadForTheTokenVersion() throws Exception {
        UUID operator = publish(1, "[]");
        template.opsForValue()
                .set(OperatorStateKeys.grants(operator, 2), "{\"roles\":[],\"permissions\":[\"order:read\"]}");

        mvc.perform(get("/orders").with(operatorJwt(operator, 1))).andExpect(status().isForbidden());
        mvc.perform(get("/orders").with(operatorJwt(operator, 2))).andExpect(status().isOk());
    }

    private UUID publish(long version, String permissions) {
        UUID operatorId = UUID.randomUUID();
        template.opsForValue()
                .set(
                        OperatorStateKeys.grants(operatorId, version),
                        "{\"roles\":[],\"permissions\":" + permissions + "}");
        return operatorId;
    }

    private static RequestPostProcessor operatorJwt(UUID operatorId, long version) {
        return jwt().jwt(token -> token.subject(operatorId.toString()).claim("ver", version));
    }
}
