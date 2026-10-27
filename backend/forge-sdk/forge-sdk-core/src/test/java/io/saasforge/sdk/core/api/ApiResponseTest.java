package io.saasforge.sdk.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ApiResponseTest {
    final JsonMapper json = JsonMapper.builder().build();

    record Order(String id, String title) {}

    @Test
    void okEnvelopeOmitsNullFields() {
        String body = json.writeValueAsString(ApiResponse.ok(new Order("o-1", "办公用品")));

        assertThat(body).isEqualTo("{\"code\":\"ok\",\"httpStatus\":200,\"data\":{\"id\":\"o-1\",\"title\":\"办公用品\"}}");
    }

    @Test
    void createdEnvelopeUses201AndCarriesTraceId() {
        String body = json.writeValueAsString(
                ApiResponse.created(new Order("o-1", "t")).withTraceId("4bf92f35"));

        assertThat(body)
                .isEqualTo("{\"code\":\"ok\",\"httpStatus\":201,\"data\":{\"id\":\"o-1\",\"title\":\"t\"},"
                        + "\"traceId\":\"4bf92f35\"}");
    }

    @Test
    void okWithoutDataOutputsOnlyCodeAndStatus() {
        assertThat(json.writeValueAsString(ApiResponse.ok())).isEqualTo("{\"code\":\"ok\",\"httpStatus\":200}");
    }

    @Test
    void pageEnvelopePutsPagingInMeta() {
        String body =
                json.writeValueAsString(ApiResponse.page(List.of(new Order("o-1", "t")), PageMeta.of(1, 20, 135)));

        assertThat(body)
                .isEqualTo("{\"code\":\"ok\",\"httpStatus\":200,\"data\":[{\"id\":\"o-1\",\"title\":\"t\"}],"
                        + "\"meta\":{\"page\":{\"number\":1,\"size\":20,\"totalElements\":135,\"totalPages\":7}}}");
    }

    @Test
    void emptyPageHasZeroTotalPages() {
        assertThat(PageMeta.of(1, 20, 0).totalPages()).isZero();
    }

    @Test
    void errorEnvelopeCarriesDeveloperAndUserTextAndFieldErrors() {
        ApiResponse<Void> error = ApiResponse.error(
                400,
                "request.invalid",
                "1 field(s) failed validation",
                "请检查填写的内容",
                List.of(new FieldError("title", "validation.notBlank", "不能为空")));

        assertThat(json.writeValueAsString(error))
                .isEqualTo("{\"code\":\"request.invalid\",\"httpStatus\":400,"
                        + "\"errorMessage\":\"1 field(s) failed validation\",\"userTip\":\"请检查填写的内容\","
                        + "\"errors\":[{\"field\":\"title\",\"code\":\"validation.notBlank\",\"userTip\":\"不能为空\"}]}");
    }

    @Test
    void errorEnvelopeWithoutFieldErrorsOmitsErrors() {
        ApiResponse<Void> error = ApiResponse.error(404, "order.notFound", "Order 1 not found", "订单不存在", null);

        assertThat(json.writeValueAsString(error)).doesNotContain("errors").doesNotContain("data");
    }
}
