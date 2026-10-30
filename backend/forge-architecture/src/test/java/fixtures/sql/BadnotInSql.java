package fixtures.sql;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

public class BadnotInSql {
    void run() {
        new LambdaQueryWrapper<>().notInSql(Object::toString, "SELECT 1");
    }
}
