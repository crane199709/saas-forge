package fixtures.externaljdbc;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.sql.Connection;
import java.sql.DriverManager;

public class ExternalConnector {
    Connection field;

    static class Nested {
        DriverManager field;
    }

    public static class WithWrapper {
        QueryWrapper<Object> field;
    }
}
