package io.saasforge.iam.permission.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ResourceManifestMapper {
    /** 在当前事务内串行化同一服务的上报，事务结束时自动释放。清单是全局表，不受 Data Policy 过滤。 */
    int lockService(@Param("service") String service);

    /** 已存在的同版本清单内容是否与给定内容相同（按 jsonb 比较）；版本不存在时返回 null。不受 Data Policy 过滤。 */
    Boolean sameContent(
            @Param("service") String service, @Param("version") String version, @Param("content") String content);

    /** 取消该服务的当前版本。上报是服务间调用，没有操作人，updated_by 写 NULL。不受 Data Policy 过滤。 */
    int clearCurrent(@Param("service") String service);

    /** 写入新版本并设为当前版本。不受 Data Policy 过滤。 */
    int insertCurrent(
            @Param("id") UUID id,
            @Param("service") String service,
            @Param("version") String version,
            @Param("content") String content);

    /** 全部服务当前版本的清单内容（JSON），按服务名排序。不受 Data Policy 过滤。 */
    List<String> currentContents();
}
