package fixtures.sql;

import org.apache.ibatis.annotations.SelectProvider;

public interface BadSelectProvider {
    @SelectProvider(type = Object.class)
    void execute();
}
