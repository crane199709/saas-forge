package com.example.retained.document.mapper;
import com.example.retained.document.service.DocumentService.Document;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
@Mapper
public interface DocumentMapper {
    /** 保存业务附件引用，租户由 SDK 注入。 */
    void insert(@Param("id") UUID id, @Param("objectId") UUID objectId, @Param("title") String title);
    /** 仅返回未删除且属于当前租户的附件。 */
    Document find(UUID id);
    /** 保留附件引用，供逻辑删除后的文件治理核对。 */
    void delete(UUID id);
}
