package io.saasforge.sdk.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSchemaTest {
    @Autowired
    MockMvc mvc;

    DocumentContext docs;

    @BeforeEach
    void loadDocs() throws Exception {
        String json = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        docs = JsonPath.parse(json);
    }

    @Test
    void successSchemaIsTheEnvelopeWithConcreteDataType() {
        String ref = docs.read("$.paths['/orders'].post.responses['201'].content['*/*'].schema['$ref']");
        String schemaName = ref.substring(ref.lastIndexOf('/') + 1);

        Map<String, Object> properties = docs.read("$.components.schemas['" + schemaName + "'].properties");
        assertThat(properties).containsKeys("code", "httpStatus", "data", "traceId", "userTip", "errors");
        String dataRef = docs.read("$.components.schemas['" + schemaName + "'].properties.data['$ref']");
        assertThat(dataRef).endsWith("/OrderView");
    }

    @Test
    void errorResponsesAreDocumentedWithTheEnvelope() {
        Map<String, Object> responses = docs.read("$.paths['/orders/{id}'].get.responses");
        // 404、405 由 springdoc 按方法签名过滤（路由级错误不属于具体接口），业务 404 来自各接口自己的 ErrorCode
        assertThat(responses).containsKeys("400", "500");
        String ref = docs.read("$.paths['/orders/{id}'].get.responses['400'].content['*/*'].schema['$ref']");
        Map<String, Object> properties =
                docs.read("$.components.schemas['" + ref.substring(ref.lastIndexOf('/') + 1) + "'].properties");
        assertThat(properties).containsKeys("code", "errorMessage", "userTip", "traceId", "errors");
    }

    @Test
    void pageQueryIsDocumentedAsPagingQueryParameters() {
        List<String> names = docs.read("$.paths['/orders'].get.parameters[*].name");
        assertThat(names).containsExactlyInAnyOrder("page", "size", "sort");
    }
}
