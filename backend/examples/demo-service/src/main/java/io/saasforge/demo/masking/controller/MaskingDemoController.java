package io.saasforge.demo.masking.controller;

import io.saasforge.demo.masking.model.MaskedProfile;
import io.saasforge.demo.masking.model.RawProfile;
import io.saasforge.demo.masking.service.MaskingDemoService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/masking")
public class MaskingDemoController {
    private final MaskingDemoService service;

    public MaskingDemoController(MaskingDemoService service) {
        this.service = service;
    }

    @GetMapping("/profiles")
    @RequiresPermission("masking:read")
    public ApiResponse<Profiles> profiles() {
        return ApiResponse.ok(new Profiles(service.profiles()));
    }

    @GetMapping("/profiles/raw")
    public ApiResponse<RawProfile> viewRaw() {
        return ApiResponse.ok(service.viewRaw());
    }

    public record Profiles(List<MaskedProfile> profiles) {}
}
