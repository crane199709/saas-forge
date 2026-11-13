package io.saasforge.demo.attachment.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.demo.attachment.entity.OrderAttachment;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderAttachmentMapper extends BaseMapper<OrderAttachment> {
    /**
     * 批量检查文件引用，包含逻辑删除附件，避免业务恢复后丢失文件。
     * 治理覆盖租户内全部引用，不按成员 Data Policy 过滤；租户拦截与 RLS 仍生效。
     */
    @InterceptorIgnore(dataPermission = "true")
    Set<UUID> referencedObjects(@Param("objectIds") List<UUID> objectIds);
}
