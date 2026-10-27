package io.saasforge.sdk.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class EnvelopeTest {
    static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    @Autowired
    MockMvc mvc;

    @Test
    void createdEnvelopeUses201AndCarriesIncomingTraceId() throws Exception {
        mvc.perform(post("/orders")
                        .header("traceparent", TRACEPARENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"办公用品\",\"amount\":128.50}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ok"))
                .andExpect(jsonPath("$.httpStatus").value(201))
                .andExpect(jsonPath("$.data.title").value("办公用品"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.errorMessage").doesNotExist())
                .andExpect(jsonPath("$.userTip").doesNotExist())
                .andExpect(jsonPath("$.meta").doesNotExist());
    }

    @Test
    void deleteReturns200WithoutData() throws Exception {
        mvc.perform(delete("/orders/o-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ok"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.traceId").isString());
    }
}
