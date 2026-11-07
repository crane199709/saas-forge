package io.saasforge.iam.schema.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 测试专用：查询当前 schema 的注释与通知 RLS 元数据；系统目录不做租户改写。 */
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface SchemaCommentMapper {
    /** 通知父表与已生成月分区；防止标准门禁因遗漏受检对象而误通过。 */
    List<String> selectNotificationTables();

    /** 未启用强制 RLS 或缺少读写策略的通知父表和月分区。 */
    List<String> selectUnprotectedNotificationTables();

    /**
     * 当前 schema 的普通表与分区父表数量，不含 Flyway 历史表。不受 Data Policy 过滤。
     *
     * @return 表数量
     */
    int countTables();

    /**
     * 缺少中文描述的表（表名）与字段（表名.字段名），不含分区子表与 Flyway 历史表。不受 Data Policy 过滤。
     *
     * @return 缺少描述的对象
     */
    List<String> selectUncommented();
}
