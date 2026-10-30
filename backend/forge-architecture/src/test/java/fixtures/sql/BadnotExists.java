package fixtures.sql;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

public class BadnotExists {
    void run() {
        new LambdaQueryWrapper<>().notExists("SELECT 1");
    }
}
