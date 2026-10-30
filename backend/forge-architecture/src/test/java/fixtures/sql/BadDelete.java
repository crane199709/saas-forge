package fixtures.sql;

import org.apache.ibatis.annotations.Delete;

public interface BadDelete {
    @Delete("SELECT 1")
    void execute();
}
