package fixtures.sql;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

public class Badapply {
    void run() {
        new LambdaQueryWrapper<>().apply("SELECT 1");
    }
}
