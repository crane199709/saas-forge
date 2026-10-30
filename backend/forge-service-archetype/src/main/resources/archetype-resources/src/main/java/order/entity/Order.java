#set( $dollar = '$' )
#set( $hash = '#' )
package ${package}.order.entity;

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
    private String title;
    private BigDecimal amount;
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
