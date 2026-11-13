package io.saasforge.iam.referencedata.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.referencedata.model.DictionaryHistoryRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 字典历史按显式租户条件与 RLS 隔离，不受 Data Policy 过滤。 */
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface DictionaryHistoryMapper {
    /** 按版本倒序读取当前租户指定字典的全部未删除历史。 */
    List<DictionaryHistoryRow> list(
            @Param("tenantId") UUID tenantId, @Param("service") String service, @Param("dictionary") String dictionary);

    /** 在持有对应字典事务锁时追加完整快照并递增版本，与字典修改使用同一事务。 */
    int append(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("service") String service,
            @Param("dictionary") String dictionary,
            @Param("snapshot") String snapshot,
            @Param("summary") String summary,
            @Param("actor") UUID actor);

    /** 租户删除时逻辑删除其全部历史；系统操作将 updated_by 设为 NULL。 */
    int deleteTenant(@Param("tenantId") UUID tenantId);
}
