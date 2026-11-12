package io.saasforge.sdk.security;

import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.json.JsonMapper;

/** 在 JSON 转换器提交响应前完成一次序列化，末尾字段失败也不会留下已发送的明文或半截 JSON。 */
@RestControllerAdvice
@Order(100)
final class FieldMaskingResponseAdvice implements ResponseBodyAdvice<Object> {
    private final JsonMapper mapper;

    FieldMaskingResponseAdvice(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return org.springframework.http.converter.json.JacksonJsonHttpMessageConverter.class.isAssignableFrom(
                converterType);
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body == null) return null;
        try {
            // JSON 树不再含业务属性及注解，发送阶段不会二次读取策略或执行引擎。
            return mapper.writer().valueToTree(body);
        } catch (RuntimeException error) {
            // Jackson 的 reference chain 或 getter 异常可能含原值；不携带 cause。
            throw ForgeException.withErrorMessage(FrameworkErrorCode.SYSTEM_ERROR, "Online field masking failed");
        }
    }
}
