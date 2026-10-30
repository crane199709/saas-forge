package fixtures.sql;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

public class BadSelect {
    void run() {
        new QueryWrapper<>().select("id");
    }
}
