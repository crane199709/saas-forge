package io.saasforge.demo.greeting.controller;

import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "示例")
@RestController
public class GreetingController {

    private final MessageSource messageSource;

    public GreetingController(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Operation(summary = "按 i18n key 返回示例文案")
    @GetMapping("/greeting")
    public ApiResponse<GreetingResponse> greeting() {
        return ApiResponse.ok(
                new GreetingResponse(messageSource.getMessage("demo.greeting", null, LocaleContextHolder.getLocale())));
    }

    public record GreetingResponse(String message) {}
}
