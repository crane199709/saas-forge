package fixtures.sql;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.sql.Timestamp;

public class Allowed {
    Timestamp time;

    void query() {
        new LambdaQueryWrapper<Object>().eq(Object::toString, "value").select(Object::toString);
        new LambdaUpdateWrapper<Object>().set(Object::toString, "value");
    }
}
