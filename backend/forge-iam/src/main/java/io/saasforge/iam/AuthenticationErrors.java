package io.saasforge.iam;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class AuthenticationErrors {
    private final MessageSource messages;

    AuthenticationErrors(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<AuthenticationErrorResponse> failed() {
        return ResponseEntity.status(401)
                .cacheControl(CacheControl.noStore())
                .body(new AuthenticationErrorResponse(
                        "auth.failed", messages.getMessage("auth.failed", null, LocaleContextHolder.getLocale())));
    }

    record AuthenticationErrorResponse(String code, String message) {}
}
