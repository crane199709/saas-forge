package fixtures.sql;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.function.Function;

public class BadReference {
    Function<String, LambdaQueryWrapper<Object>> fragment = new LambdaQueryWrapper<Object>()::last;
}
