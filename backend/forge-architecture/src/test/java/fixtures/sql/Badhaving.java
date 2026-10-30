package fixtures.sql;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

public class Badhaving {
    void run() {
        new LambdaQueryWrapper<>().having("SELECT 1");
    }
}
