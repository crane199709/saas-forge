package io.saasforge.demo.order.controller;

import io.saasforge.demo.order.entity.Order;
import io.saasforge.demo.order.service.OrderService;
import io.saasforge.sdk.audit.AuditLog;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.referencedata.DictionaryValue;
import io.saasforge.sdk.security.RequiresPermission;
import io.saasforge.sdk.web.PageQuery;
import io.saasforge.sdk.web.SortableFields;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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

@Tag(name = "订单")
@RestController
@RequestMapping("/orders")
class OrderController {
    private final OrderService orderService;

    OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(summary = "在当前租户下创建订单")
    @PostMapping
    @RequiresPermission("order:create")
    @AuditLog(action = "order.create", resourceType = "order", resourceId = "#result.data().id()")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<OrderResponse> create(@Valid @RequestBody OrderRequest request) {
        return ApiResponse.created(
                OrderResponse.from(orderService.create(request.title(), request.amount(), request.customerSource())));
    }

    @Operation(summary = "分页列出当前租户中当前成员数据范围内的订单")
    @GetMapping
    @RequiresPermission("order:read")
    ApiResponse<List<OrderResponse>> list(@SortableFields({"createdAt", "amount"}) PageQuery query) {
        List<OrderResponse> items =
                orderService.list(query).stream().map(OrderResponse::from).toList();
        return ApiResponse.page(items, query.toPageMeta(orderService.count()));
    }

    @Operation(summary = "读取当前租户的订单")
    @GetMapping("/{id}")
    @RequiresPermission("order:read")
    ApiResponse<OrderResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(OrderResponse.from(orderService.get(id)));
    }

    @Operation(summary = "修改当前租户的订单")
    @PutMapping("/{id}")
    @RequiresPermission("order:update")
    @AuditLog(action = "order.update", resourceType = "order", resourceId = "#id")
    ApiResponse<OrderResponse> update(@PathVariable UUID id, @Valid @RequestBody OrderRequest request) {
        return ApiResponse.ok(OrderResponse.from(
                orderService.update(id, request.title(), request.amount(), request.customerSource())));
    }

    @Operation(summary = "删除当前租户的订单")
    @DeleteMapping("/{id}")
    @RequiresPermission("order:delete")
    @AuditLog(action = "order.delete", resourceType = "order", resourceId = "#id")
    ApiResponse<Void> delete(@PathVariable UUID id) {
        orderService.delete(id);
        return ApiResponse.ok();
    }

    /** @param customerSource 客户来源，字典 customer-source 的可选项编码，可不填 */
    record OrderRequest(
            String title,
            BigDecimal amount,
            @DictionaryValue("customer-source") String customerSource) {}

    /**
     * @param number Business Number，如 {@code SO-20261104-0007}；引入编号前创建的订单为 null
     * @param customerSource 客户来源的字典项编码，只返回编码，名称由前端按字典翻译；未填写时为 null
     * @param status 订单状态：unpaid（未支付）、closed（已关闭）
     * @param ownerId 数据归属的 Membership，系统创建的订单为 null
     * @param departmentPath 数据归属部门的路径，归属成员未分配部门时为 null
     */
    record OrderResponse(
            UUID id,
            String number,
            String title,
            BigDecimal amount,
            String customerSource,
            String status,
            UUID ownerId,
            String departmentPath,
            OffsetDateTime createdAt) {
        static OrderResponse from(Order order) {
            return new OrderResponse(
                    order.getId(),
                    order.getNumber(),
                    order.getTitle(),
                    order.getAmount(),
                    order.getCustomerSource(),
                    order.getStatus(),
                    order.getOwnerId(),
                    order.getDepartmentPath(),
                    order.getCreatedAt());
        }
    }
}
