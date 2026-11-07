package io.saasforge.iam.subapp.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.subapp.model.SubAppDetailRow;
import io.saasforge.iam.subapp.model.SubAppEntryRow;
import io.saasforge.iam.subapp.model.SubAppRequirementRow;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** App Registry 的副本。由 platform 广播的 SubAppChanged 写入；平台数据，不属于任何租户，不受 Data Policy 过滤。 */
// iam 的表不按租户行拦截隔离（ADR-0001）；本表也没有租户列，消费事件时不能被注入租户条件。
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface SubAppMapper {
    /**
     * 应用变更：未登记时登记，已登记且已应用的序号更旧时整体更新（含删除标记）。
     *
     * @return 应用了变更为 1；已应用同一或更新的序号时为 0
     */
    int apply(
            @Param("code") String code,
            @Param("name") String name,
            @Param("version") String version,
            @Param("revision") long revision,
            @Param("deleted") boolean deleted);

    /** 删除某 Sub-App 的全部入口地址，随后整体写入新的。 */
    void clearEntries(@Param("code") String code);

    /** 删除某 Sub-App 的全部最低共享依赖版本，随后整体写入新的。 */
    void clearRequirements(@Param("code") String code);

    /** 写入某 Sub-App 各环境的入口地址，须先清除旧的；{@code rows} 不能为空。 */
    void insertEntries(@Param("code") String code, @Param("rows") Collection<SubAppDetailRow> rows);

    /** 写入某 Sub-App 的最低共享依赖版本，须先清除旧的；{@code rows} 不能为空。 */
    void insertRequirements(@Param("code") String code, @Param("rows") Collection<SubAppDetailRow> rows);

    /** 给定编码中未删除、且登记了该环境入口地址的 Sub-App，按编码排序。 */
    List<SubAppEntryRow> loadable(@Param("codes") Collection<String> codes, @Param("environment") String environment);

    /** 给定编码的最低共享依赖版本，按编码、包名排序。 */
    List<SubAppRequirementRow> requirements(@Param("codes") Collection<String> codes);
}
