package fixtures.sql;

import org.apache.ibatis.annotations.InsertProvider;

public interface BadInsertProvider {
    @InsertProvider(type = Object.class)
    void execute();
}
