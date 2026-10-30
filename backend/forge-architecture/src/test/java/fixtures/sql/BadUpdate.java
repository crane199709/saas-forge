package fixtures.sql;

import org.apache.ibatis.annotations.Update;

public interface BadUpdate {
    @Update("SELECT 1")
    void execute();
}
