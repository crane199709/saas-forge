package fixtures.sql;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

public class Badexists {
    void run() {
        new LambdaQueryWrapper<>().exists("SELECT 1");
    }
}
