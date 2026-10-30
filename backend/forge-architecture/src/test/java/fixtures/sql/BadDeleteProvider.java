package fixtures.sql;

import org.apache.ibatis.annotations.DeleteProvider;

public interface BadDeleteProvider {
    @DeleteProvider(type = Object.class)
    void execute();
}
