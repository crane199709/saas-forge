package io.saasforge.sdk.security;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 方法安全在 Controller 内抛出的安全异常原样抛回过滤链，交给 {@code ExceptionTranslationFilter} 与信封处理器。
 *
 * <p>否则服务的兜底 {@code @ExceptionHandler(Exception.class)}（如 forge-sdk-web）会先捕获并映射为 500。以最高优先级声明，
 * Spring MVC 只会选中这里的处理方法；它重新抛出原异常时 MVC 不再尝试其他处理方法，异常继续向外传播。本模块因此不需要依赖 forge-sdk-web。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class SecurityExceptionPassthroughAdvice {

    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    void rethrow(RuntimeException e) {
        throw e;
    }
}
