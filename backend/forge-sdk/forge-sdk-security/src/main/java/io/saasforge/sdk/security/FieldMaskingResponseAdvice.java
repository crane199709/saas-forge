package io.saasforge.sdk.security;

import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.util.WebUtils;

/** 让真实 JSON 转换器在提交响应前完成序列化，保留其 mapper、声明类型与全部写入 hints。 */
@RestControllerAdvice
@Order(100)
final class FieldMaskingResponseAdvice implements ResponseBodyAdvice<Object>, WebMvcConfigurer {
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return JacksonJsonHttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (response instanceof ServletServerHttpResponse servlet) {
            var selective = WebUtils.getNativeResponse(
                    servlet.getServletResponse(), FieldMaskingResponseFilter.SelectiveResponse.class);
            if (selective != null) {
                selective.enable();
            }
        }
        return body;
    }

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        var delegates = List.copyOf(resolvers);
        resolvers.add(0, (request, response, handler, error) -> {
            var selective = WebUtils.getNativeResponse(response, FieldMaskingResponseFilter.SelectiveResponse.class);
            if (!(error instanceof HttpMessageConversionException) || selective == null || !selective.enabled()) {
                return null;
            }
            // 仅清洗已选中 JSON 通道的转换异常；继续使用应用现有的信封、文案与日志处理。
            // Jackson 的 reference chain 或 getter 异常可能含原值，不携带 cause。
            var safe = ForgeException.withErrorMessage(FrameworkErrorCode.SYSTEM_ERROR, "Online field masking failed");
            for (var resolver : delegates) {
                var result = resolver.resolveException(request, response, handler, safe);
                if (result != null) {
                    return result;
                }
            }
            return null;
        });
    }
}
