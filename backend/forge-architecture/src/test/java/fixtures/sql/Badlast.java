package fixtures.sql;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

public class Badlast {
    void run() {
        new LambdaQueryWrapper<>().last("SELECT 1");
    }
}
