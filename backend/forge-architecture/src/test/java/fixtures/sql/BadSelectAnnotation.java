package fixtures.sql;

import org.apache.ibatis.annotations.Select;

public interface BadSelectAnnotation {
    @Select("SELECT 1")
    void execute();
}
