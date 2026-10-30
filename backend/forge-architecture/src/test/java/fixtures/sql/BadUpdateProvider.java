package fixtures.sql;

import org.apache.ibatis.annotations.UpdateProvider;

public interface BadUpdateProvider {
    @UpdateProvider(type = Object.class)
    void execute();
}
