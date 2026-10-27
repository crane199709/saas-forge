package io.saasforge.demo.order.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.sdk.web.PageQuery;
import org.junit.jupiter.api.Test;

class OrderSortTest {

    @Test
    void whitelistedFieldMapsToSnakeCaseColumnAndKeepsDirection() {
        OrderSort sort = OrderSort.from(new PageQuery.Sort("createdAt", PageQuery.Direction.DESC));

        assertThat(sort.column()).isEqualTo("created_at");
        assertThat(sort.direction()).isEqualTo(PageQuery.Direction.DESC);
    }

    @Test
    void singleWordFieldIsItsOwnColumn() {
        assertThat(OrderSort.from(new PageQuery.Sort("amount", PageQuery.Direction.ASC))
                        .column())
                .isEqualTo("amount");
    }
}
