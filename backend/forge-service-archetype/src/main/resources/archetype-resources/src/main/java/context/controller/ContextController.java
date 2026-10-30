#set( $dollar = '$' )
#set( $hash = '#' )
package ${package}.context.controller;

import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "示例上下文")
@RestController
class ContextController {
    @Operation(summary = "返回经过 SDK 验签的当前身份、租户与成员关系")
    @GetMapping("/context")
    ApiResponse<TenantContextResponse> context() {
        var context = TenantContext.current();
        return ApiResponse.ok(new TenantContextResponse(
                context.identityId(), context.tenantId(), context.membershipId(), context.version()));
    }

    record TenantContextResponse(UUID identityId, UUID tenantId, UUID membershipId, long version) {}
}
