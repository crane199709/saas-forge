package io.saasforge.demo.order.controller;

import io.saasforge.demo.order.entity.Order;
import io.saasforge.demo.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
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
    @ResponseStatus(HttpStatus.CREATED)
    OrderResponse create(@RequestBody OrderRequest request) {
        return OrderResponse.from(orderService.create(request.title(), request.amount()));
    }

    @Operation(summary = "列出当前租户的订单")
    @GetMapping
    List<OrderResponse> list() {
        return orderService.list().stream().map(OrderResponse::from).toList();
    }

    @Operation(summary = "读取当前租户的订单")
    @GetMapping("/{id}")
    OrderResponse get(@PathVariable UUID id) {
        return OrderResponse.from(orderService.get(id));
    }

    @Operation(summary = "修改当前租户的订单")
    @PutMapping("/{id}")
    OrderResponse update(@PathVariable UUID id, @RequestBody OrderRequest request) {
        return OrderResponse.from(orderService.update(id, request.title(), request.amount()));
    }

    @Operation(summary = "删除当前租户的订单")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        orderService.delete(id);
    }

    record OrderRequest(String title, BigDecimal amount) {}

    record OrderResponse(UUID id, String title, BigDecimal amount) {
        static OrderResponse from(Order order) {
            return new OrderResponse(order.getId(), order.getTitle(), order.getAmount());
        }
    }
}
