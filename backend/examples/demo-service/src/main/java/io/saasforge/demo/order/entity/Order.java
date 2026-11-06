package io.saasforge.demo.order.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.abac.data.LtreeTypeHandler;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.math.BigDecimal;
import java.util.UUID;

/** 不声明 tenant_id：租户列由 SDK 在 SQL 中注入，业务代码无从读写。 */
@TableName("orders")
public class Order extends AuditableEntity {
    private UUID id;
    // Business Number，创建时生成，之后不随接口修改；引入编号前创建的订单为 null
    private String number;
    private String title;
    private BigDecimal amount;
    private String status;
    // 字典 customer-source 的字典项编码；引入客户来源前创建的订单为 null
    private String customerSource;
    // 数据归属，是 Data Resource「order」的资源属性；创建时取当前成员与其主部门，之后不随接口修改。
    private UUID ownerId;

    @TableField(typeHandler = LtreeTypeHandler.class)
    private String departmentPath;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCustomerSource() {
        return customerSource;
    }

    public void setCustomerSource(String customerSource) {
        this.customerSource = customerSource;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public String getDepartmentPath() {
        return departmentPath;
    }

    public void setDepartmentPath(String departmentPath) {
        this.departmentPath = departmentPath;
    }
}
