package io.saasforge.sdk.abac.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import io.saasforge.sdk.abac.AttributeType;
import io.saasforge.sdk.abac.SqlParameter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class DataPermissionInnerInterceptorTest {
    private final Configuration configuration = new Configuration();

    @Test
    void policyParametersAreInsertedWhereTheirMarkersAppearAmongTheOriginalPlaceholders() {
        UUID me = UUID.randomUUID();
        var policy = new SqlParameter(AttributeType.UUID, false, me);
        BoundSql boundSql = boundSql("SELECT ...", "title", "limit", "offset");

        DataPermissionInnerInterceptor.bind(
                "SELECT * FROM orders WHERE title = ? AND (orders.owner_id = CAST(:forge_dp_0 AS uuid)) "
                        + "AND note = 'why?' LIMIT ? OFFSET ?",
                List.of(policy),
                PluginUtils.mpBoundSql(boundSql),
                boundSql,
                configuration);

        assertThat(boundSql.getSql())
                .isEqualTo("SELECT * FROM orders WHERE title = ? AND (orders.owner_id = CAST(? AS uuid)) "
                        + "AND note = 'why?' LIMIT ? OFFSET ?");
        assertThat(boundSql.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactly("title", "__forge_dp_0", "limit", "offset");
        assertThat(boundSql.getAdditionalParameter("__forge_dp_0")).isEqualTo(policy);
    }

    @Test
    void placeholderCountThatDoesNotMatchTheMappingsIsRejected() {
        BoundSql boundSql = boundSql("SELECT ...", "id");

        assertThatThrownBy(() -> DataPermissionInnerInterceptor.bind(
                        "SELECT * FROM orders WHERE id = ? AND x = ?",
                        List.of(),
                        PluginUtils.mpBoundSql(boundSql),
                        boundSql,
                        configuration))
                .isInstanceOf(IllegalStateException.class);
    }

    private BoundSql boundSql(String sql, String... properties) {
        var mappings = new ArrayList<ParameterMapping>();
        for (String property : properties) {
            mappings.add(new ParameterMapping.Builder(configuration, property, Object.class).build());
        }
        return new BoundSql(configuration, sql, mappings, null);
    }
}
