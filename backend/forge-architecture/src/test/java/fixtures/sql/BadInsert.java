package fixtures.sql;

import org.apache.ibatis.annotations.Insert;

public interface BadInsert {
    @Insert("SELECT 1")
    void execute();
}
