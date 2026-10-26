package io.saasforge.demo.order.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.util.UUID;

/** 不声明 tenant_id：租户列由 SDK 在 SQL 中注入，业务代码无从读写。 */
@TableName("orders")
public class Order {
    private UUID id;
    private String title;
    private BigDecimal amount;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
