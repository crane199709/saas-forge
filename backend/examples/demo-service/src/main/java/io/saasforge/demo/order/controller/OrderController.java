package io.saasforge.demo.order.controller;

import io.saasforge.demo.order.entity.Order;
import io.saasforge.demo.order.service.OrderService;
import io.saasforge.sdk.audit.AuditLog;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import io.saasforge.sdk.web.PageQuery;
import io.saasforge.sdk.web.SortableFields;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
    ApiResponse<OrderResponse> create(@RequestBody OrderRequest request) {
        return ApiResponse.created(OrderResponse.from(orderService.create(request.title(), request.amount())));
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
    ApiResponse<OrderResponse> update(@PathVariable UUID id, @RequestBody OrderRequest request) {
        return ApiResponse.ok(OrderResponse.from(orderService.update(id, request.title(), request.amount())));
    }

    @Operation(summary = "删除当前租户的订单")
    @DeleteMapping("/{id}")
    @RequiresPermission("order:delete")
    @AuditLog(action = "order.delete", resourceType = "order", resourceId = "#id")
    ApiResponse<Void> delete(@PathVariable UUID id) {
        orderService.delete(id);
        return ApiResponse.ok();
    }

    record OrderRequest(String title, BigDecimal amount) {}

    /**
     * @param ownerId 数据归属的 Membership，系统创建的订单为 null
     * @param departmentPath 数据归属部门的路径，归属成员未分配部门时为 null
     */
    record OrderResponse(
            UUID id, String title, BigDecimal amount, UUID ownerId, String departmentPath, OffsetDateTime createdAt) {
        static OrderResponse from(Order order) {
            return new OrderResponse(
                    order.getId(),
                    order.getTitle(),
                    order.getAmount(),
                    order.getOwnerId(),
                    order.getDepartmentPath(),
                    order.getCreatedAt());
        }
    }
}
