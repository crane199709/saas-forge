package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.demo.schema.mapper.SchemaCommentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 编码规范要求每张表和每个字段都有中文描述：新增表或字段漏写注释时，迁移后在这里失败。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SchemaCommentTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        TestReferenceData.register(registry);
    }

    @Autowired
    SchemaCommentMapper mapper;

    @Test
    void everyTableAndColumnHasComment() {
        assertThat(mapper.countTables()).isPositive();
        assertThat(mapper.selectUncommented()).isEmpty();
    }
}
