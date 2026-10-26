package io.saasforge.demo.greeting.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(GreetingController.class)
class GreetingControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    @WithMockUser
    void greetingIsRenderedFromMessageKey() {
        assertThat(mvc.get().uri("/greeting"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.data.message")
                .isEqualTo("你好，这是 demo-service 通过 i18n key 渲染的示例文案");
    }
}
