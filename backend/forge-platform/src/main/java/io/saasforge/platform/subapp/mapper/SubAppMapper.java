package io.saasforge.platform.subapp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.platform.subapp.entity.SubApp;
import io.saasforge.platform.subapp.model.SubAppRow;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** App Registry。平台全局表，不受 Data Policy 过滤。 */
@Mapper
public interface SubAppMapper extends BaseMapper<SubApp> {
    /** 下一个变更序号，全局递增。 */
    long nextRevision();

    /** 未删除的 Sub-App，并对该行加排他锁至事务结束，供修改与删除使用；不存在时为 null。 */
    SubApp lockExclusive(@Param("id") UUID id);

    /** 某 Sub-App 各环境的入口地址，按环境名排序。 */
    List<SubAppRow> entries(@Param("subAppId") UUID subAppId);

    /** 某 Sub-App 所需的最低共享依赖版本，按包名排序。 */
    List<SubAppRow> requirements(@Param("subAppId") UUID subAppId);

    /** 删除某 Sub-App 的全部入口地址，随后整体写入新的。 */
    void clearEntries(@Param("subAppId") UUID subAppId);

    /** 删除某 Sub-App 的全部最低共享依赖版本，随后整体写入新的。 */
    void clearRequirements(@Param("subAppId") UUID subAppId);

    /** 写入某 Sub-App 各环境的入口地址，须先清除旧的；{@code rows} 不能为空。 */
    void insertEntries(@Param("subAppId") UUID subAppId, @Param("rows") Collection<SubAppRow> rows);

    /** 写入某 Sub-App 的最低共享依赖版本，须先清除旧的；{@code rows} 不能为空。 */
    void insertRequirements(@Param("subAppId") UUID subAppId, @Param("rows") Collection<SubAppRow> rows);
}
