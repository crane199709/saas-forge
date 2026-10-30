package fixtures.sql;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

public class BadinSql {
    void run() {
        new LambdaQueryWrapper<>().inSql(Object::toString, "SELECT 1");
    }
}
