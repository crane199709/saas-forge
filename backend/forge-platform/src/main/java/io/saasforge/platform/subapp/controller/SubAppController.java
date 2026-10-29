package io.saasforge.platform.subapp.controller;

import io.saasforge.platform.subapp.model.SubAppDetails;
import io.saasforge.platform.subapp.service.SubAppService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 注册表列表也供套餐面板选择 Sub-App，只要求已认证；维护要求管理权限。
@Tag(name = "App Registry")
@RestController
@RequestMapping("/sub-apps")
class SubAppController {
    static final String MANAGE = "platform:sub-app:manage";

    // 与 Plan Feature Pack 中的 Sub-App 编码规则一致
    private static final String CODE = "[a-z][a-z0-9-]{0,63}";
    private static final String ENVIRONMENT = "[a-z][a-z0-9-]{0,31}";
    // 入口必须是 http(s) 绝对地址：Shell 以 ESM 方式跨源加载
    private static final String REMOTE_ENTRY = "https?://[^\\s]+";
    private static final String PACKAGE = "(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*";
    private static final String MIN_VERSION = "\\d{1,9}\\.\\d{1,9}\\.\\d{1,9}";

    private final SubAppService apps;

    SubAppController(SubAppService apps) {
        this.apps = apps;
    }

    @Operation(summary = "列出 App Registry 中的全部 Sub-App，按编码排序")
    @GetMapping
    ApiResponse<List<SubAppResponse>> list() {
        return ApiResponse.ok(apps.list().stream().map(SubAppResponse::from).toList());
    }

    @Operation(summary = "登记 Sub-App")
    @PostMapping
    @RequiresPermission(MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<SubAppResponse> create(@Valid @RequestBody CreateSubAppRequest request) {
        return ApiResponse.created(SubAppResponse.from(apps.create(
                request.code(),
                request.name().strip(),
                request.version().strip(),
                request.remoteEntries(),
                request.sharedRequirements())));
    }

    @Operation(summary = "整体替换 Sub-App 的名称、版本、各环境入口地址与最低共享依赖版本；Shell App 刷新后按新地址加载，无需重新发布")
    @PutMapping("/{id}")
    @RequiresPermission(MANAGE)
    ApiResponse<SubAppResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateSubAppRequest request) {
        return ApiResponse.ok(SubAppResponse.from(apps.update(
                id,
                request.name().strip(),
                request.version().strip(),
                request.remoteEntries(),
                request.sharedRequirements())));
    }

    @Operation(summary = "删除 Sub-App；Shell App 随即不再加载它")
    @DeleteMapping("/{id}")
    @RequiresPermission(MANAGE)
    ApiResponse<Void> delete(@PathVariable UUID id) {
        apps.delete(id);
        return ApiResponse.ok();
    }

    /**
     * @param version 当前版本，仅用于展示与排查
     * @param remoteEntries 各环境的 {@code remoteEntry.js} 地址，键为环境名
     * @param sharedRequirements 所需的最低共享依赖版本，键为包名，值为 {@code x.y.z}；Shell 提供的版本更低时拒绝加载
     */
    record CreateSubAppRequest(
            @NotBlank @Pattern(regexp = CODE) String code,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 64) String version,

            @NotEmpty
            Map<
                            @NotBlank @Pattern(regexp = ENVIRONMENT) String,
                            @NotBlank @Size(max = 2048) @Pattern(regexp = REMOTE_ENTRY) String>
                    remoteEntries,

            @NotNull
            Map<
                            @NotBlank @Size(max = 128) @Pattern(regexp = PACKAGE) String,
                            @NotBlank @Pattern(regexp = MIN_VERSION) String>
                    sharedRequirements) {}

    /** 字段含义同 {@link CreateSubAppRequest}，编码不可修改。 */
    record UpdateSubAppRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 64) String version,

            @NotEmpty
            Map<
                            @NotBlank @Pattern(regexp = ENVIRONMENT) String,
                            @NotBlank @Size(max = 2048) @Pattern(regexp = REMOTE_ENTRY) String>
                    remoteEntries,

            @NotNull
            Map<
                            @NotBlank @Size(max = 128) @Pattern(regexp = PACKAGE) String,
                            @NotBlank @Pattern(regexp = MIN_VERSION) String>
                    sharedRequirements) {}

    record SubAppResponse(
            UUID id,
            String code,
            String name,
            String version,
            Map<String, String> remoteEntries,
            Map<String, String> sharedRequirements,
            OffsetDateTime updatedAt) {
        static SubAppResponse from(SubAppDetails details) {
            var app = details.app();
            return new SubAppResponse(
                    app.getId(),
                    app.getCode(),
                    app.getName(),
                    app.getVersion(),
                    details.remoteEntries(),
                    details.sharedRequirements(),
                    app.getUpdatedAt());
        }
    }
}
