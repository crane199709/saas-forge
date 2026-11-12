package io.saasforge.iam.servicecredential.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.servicecredential.model.ServiceCredentialRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 服务凭证属于 Platform Realm，不按租户隔离，不受 Data Policy 过滤。 */
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface ServiceCredentialMapper {
    /** 按摘要查询，包括停用记录，防止同值静态配置复活凭证。 */
    ServiceCredentialRow findByDigest(@Param("digest") String digest);
    /** 按 ID 查询未删除凭证。 */
    ServiceCredentialRow find(@Param("id") UUID id);
    /** 查询指定服务的未删除凭证；service 为 null 时列出全部。 */
    List<ServiceCredentialRow> list(@Param("service") String service);
    /** 创建有效凭证并返回已持久化的行。 */
    ServiceCredentialRow insert(
            @Param("id") UUID id,
            @Param("service") String service,
            @Param("digest") String digest,
            @Param("actor") UUID actor);
    /** 仍有效时更新最近使用时间；并发停用已提交时返回 0。 */
    int touch(@Param("id") UUID id);
    /** 原子停用有效凭证，返回修改行数。 */
    int disable(@Param("id") UUID id, @Param("actor") UUID actor);
}
