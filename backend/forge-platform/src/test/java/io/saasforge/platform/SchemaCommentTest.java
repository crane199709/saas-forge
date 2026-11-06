package io.saasforge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.platform.schema.mapper.SchemaCommentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 编码规范要求每张表和每个字段都有中文描述：新增表或字段漏写注释时，迁移后在这里失败。 */
class SchemaCommentTest extends PlatformIntegrationTest {
    @Autowired
    SchemaCommentMapper mapper;

    @Test
    void everyTableAndColumnHasComment() {
        assertThat(mapper.countTables()).isPositive();
        assertThat(mapper.selectUncommented()).isEmpty();
    }
}
