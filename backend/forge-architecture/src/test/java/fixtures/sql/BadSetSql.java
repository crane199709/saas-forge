package fixtures.sql;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

public class BadSetSql {
    void run() {
        new LambdaUpdateWrapper<>().setSql("id = 1");
    }
}
