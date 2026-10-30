package io.saasforge.iam.provisioning.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 测试专用：读出 SDK 写入 Outbox 的事件，验证初始化回报与 Audit Event。 */
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

    /**
     * 某类事件的全部消息体，不论发布时的租户，按写入顺序排列。不受 Data Policy 过滤。
     *
     * @param tag 事件类型名
     * @return JSON 消息体
     */
    List<String> payloadsOf(@Param("tag") String tag);
}
