package io.saasforge.sdk.web;

import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ErrorCode;
import io.saasforge.sdk.core.api.ForgeException;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 只为测试 SDK 行为的最小业务服务。 */
@SpringBootApplication
class TestApplication {

    /** 模拟在 DispatcherServlet 之前失败的过滤器（如 Spring Session 读取 Redis），错误经容器转发到 /error。 */
    @Bean
    Filter failingFilter() {
        return (request, response, chain) -> {
            switch (((HttpServletRequest) request).getRequestURI()) {
                case "/filter-failures/redis" ->
                    throw new DataAccessResourceFailureException("Unable to connect to Redis at 10.0.0.1:6379");
                case "/filter-failures/boom" -> throw new IllegalStateException("database password=secret leaked");
                case "/filter-failures/rejected" -> ((HttpServletResponse) response).sendError(403);
                default -> chain.doFilter(request, response);
            }
        };
    }

    enum OrderErrorCode implements ErrorCode {
        NOT_FOUND("order.notFound", 404),
        UNTRANSLATED("order.untranslated", 409),
        STORAGE_BROKEN("order.storageBroken", 500);

        private final String code;
        private final int httpStatus;

        OrderErrorCode(String code, int httpStatus) {
            this.code = code;
            this.httpStatus = httpStatus;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public int httpStatus() {
            return httpStatus;
        }
    }

    record OrderView(String id, String title, BigDecimal amount) {}

    record CreateOrder(@NotBlank String title, @Positive BigDecimal amount) {}

    @RestController
    @RequestMapping("/orders")
    static class OrderController {
        private static final Logger log = LoggerFactory.getLogger(OrderController.class);

        // springdoc 无法从返回值推断 201，创建接口用 @ResponseStatus 让文档与实际状态码一致
        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        ApiResponse<OrderView> create(@Valid @RequestBody CreateOrder request) {
            return ApiResponse.created(new OrderView("o-1", request.title(), request.amount()));
        }

        @GetMapping
        ApiResponse<List<OrderView>> list(@SortableFields({"createdAt", "amount"}) PageQuery query) {
            log.info("listing orders page={} size={}", query.page(), query.size());
            return ApiResponse.page(List.of(new OrderView("o-1", "t", BigDecimal.TEN)), query.toPageMeta(135));
        }

        @GetMapping("/{id}")
        ApiResponse<OrderView> get(@PathVariable String id) {
            if ("missing".equals(id)) {
                throw ForgeException.withErrorMessage(
                        OrderErrorCode.NOT_FOUND, "Order " + id + " not found in current tenant", id);
            }
            if ("untranslated".equals(id)) {
                throw new ForgeException(OrderErrorCode.UNTRANSLATED);
            }
            if ("broken".equals(id)) {
                throw new ForgeException(OrderErrorCode.STORAGE_BROKEN);
            }
            if ("redis-down".equals(id)) {
                throw new DataAccessResourceFailureException("Unable to connect to Redis at 10.0.0.1:6379");
            }
            if ("boom".equals(id)) {
                throw new IllegalStateException("database password=secret leaked");
            }
            return ApiResponse.ok(new OrderView(id, "t", BigDecimal.ONE));
        }

        @GetMapping("/{id}/amount")
        ApiResponse<Integer> amount(@PathVariable int id) {
            return ApiResponse.ok(id);
        }

        @DeleteMapping("/{id}")
        ApiResponse<Void> delete(@PathVariable String id) {
            return ApiResponse.ok();
        }
    }
}
