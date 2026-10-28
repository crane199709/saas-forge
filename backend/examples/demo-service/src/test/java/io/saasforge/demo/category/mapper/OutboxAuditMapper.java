package io.saasforge.demo.category.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 测试专用：读出 SDK 写入 Outbox 的事件，验证初始化回报。Outbox 是服务级基础设施表，按参数中的租户过滤。 */
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface OutboxAuditMapper {
    /**
     * 某租户某类事件的消息体，按写入顺序排列。不受 Data Policy 过滤。
     *
     * @param tenantId 发布时的租户
     * @param tag 事件类型名
     * @return JSON 消息体
     */
    List<String> payloads(@Param("tenantId") UUID tenantId, @Param("tag") String tag);
}
