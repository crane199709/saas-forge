#set( $dollar = '$' )
#set( $hash = '#' )
package ${package};

import static org.assertj.core.api.Assertions.assertThat;

import ${package}.schema.mapper.SchemaCommentMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 迁移后的 schema 检查。编码规范要求每张表和每个字段都有中文描述：新增表或字段漏写注释时在这里失败。
 *
 * <p>带表的 SDK starter 在 jar 中附 {@code io/saasforge/sdk/<模块>/schema.sql}，服务须复制为 Flyway 迁移：POM 新增这类
 * starter 或升级后 SDK 新增了表，而迁移没跟上时也在这里失败，不必等到运行时才报表不存在。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchemaCommentTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();
    static final SharedContainers.Redis redis = SharedContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", redis::database);
        TestTokens.register(registry);
    }

    @Autowired
    SchemaCommentMapper mapper;

    @Test
    void everyTableAndColumnHasComment() {
        assertThat(mapper.countTables()).isPositive();
        assertThat(mapper.selectUncommented()).isEmpty();
    }

    @Test
    void everyTableOfDeclaredSdkStartersExistsAfterMigration() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        List<String> starters = new ArrayList<>();
        Matcher artifact =
                Pattern.compile("<artifactId>(forge-sdk-[a-z-]+)</artifactId>").matcher(pom);
        while (artifact.find()) {
            starters.add(artifact.group(1));
        }
        List<String> required = new ArrayList<>();
        Resource[] schemas =
                new PathMatchingResourcePatternResolver().getResources("classpath*:io/saasforge/sdk/*/schema.sql");
        for (Resource schema : schemas) {
            // 本地仓库路径形如 .../io/saasforge/forge-sdk-mq/<版本>/forge-sdk-mq-<版本>.jar，以目录名对应 POM 中的 artifactId
            String location = schema.getURL().toString();
            if (starters.stream().noneMatch(starter -> location.contains("/" + starter + "/"))) {
                continue;
            }
            Matcher table = Pattern.compile("(?i)CREATE TABLE (?:IF NOT EXISTS )?(\\w+)")
                    .matcher(schema.getContentAsString(StandardCharsets.UTF_8));
            while (table.find()) {
                required.add(table.group(1));
            }
        }
        assertThat(required)
                .as("默认的 forge-sdk-mq 带表，找不到说明 schema.sql 未按 POM 匹配")
                .isNotEmpty();
        assertThat(mapper.selectTableNames()).containsAll(required);
    }
}
