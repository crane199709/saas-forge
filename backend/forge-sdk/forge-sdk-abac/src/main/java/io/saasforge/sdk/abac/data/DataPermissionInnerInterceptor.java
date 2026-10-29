package io.saasforge.sdk.abac.data;

import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.BaseMultiTableInnerInterceptor;
import io.saasforge.sdk.abac.DataResourceDefinition;
import io.saasforge.sdk.abac.SqlParameter;
import io.saasforge.sdk.abac.SqlPredicate;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * 把当前成员在 Data Resource 上的策略编译为参数化谓词，注入涉及该资源表的查询、更新与删除（含 JOIN 与子查询中的表）。
 *
 * <p>编译器输出的 {@code ?} 先换成命名标记再交给 JSqlParser 合并进语句，改写完成后按标记在最终 SQL 中的位置把参数插入
 * MyBatis 的参数映射，与原有 {@code ?} 的顺序保持一致；值始终以参数绑定，SQL 文本中只有清单声明并校验过的列名。
 *
 * <p>INSERT 不过滤，新对象是否在数据范围内由业务代码以 {@link DataPermission#check} 校验。Mapper 方法可用
 * {@code @InterceptorIgnore(dataPermission = "true")} 声明不受数据权限过滤。
 */
final class DataPermissionInnerInterceptor extends BaseMultiTableInnerInterceptor {
    private static final String MARKER = ":forge_dp_";
    private static final String PROPERTY = "__forge_dp_";
    private static final ThreadLocal<Rewrite> CURRENT = new ThreadLocal<>();
    private static final SqlParameterTypeHandler TYPE_HANDLER = new SqlParameterTypeHandler();

    private final DataPermission permission;

    DataPermissionInnerInterceptor(DataPermission permission) {
        this.permission = permission;
    }

    @Override
    public void beforeQuery(
            Executor executor,
            MappedStatement ms,
            Object parameter,
            RowBounds rowBounds,
            ResultHandler resultHandler,
            BoundSql boundSql) {
        if (InterceptorIgnoreHelper.willIgnoreDataPermission(ms.getId())) return;
        rewrite(boundSql, ms, false);
    }

    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        PluginUtils.MPStatementHandler handler = PluginUtils.mpStatementHandler(sh);
        MappedStatement ms = handler.mappedStatement();
        SqlCommandType command = ms.getSqlCommandType();
        if (command != SqlCommandType.UPDATE && command != SqlCommandType.DELETE) return;
        if (InterceptorIgnoreHelper.willIgnoreDataPermission(ms.getId())) return;
        rewrite(handler.boundSql(), ms, true);
    }

    private void rewrite(BoundSql boundSql, MappedStatement ms, boolean multi) {
        PluginUtils.MPBoundSql mpBs = PluginUtils.mpBoundSql(boundSql);
        String sql = mpBs.sql();
        if (!mentionsResourceTable(sql)) return;
        DataPermission.Subject subject = permission.currentSubjectOf();
        if (subject == null) return;
        var rewrite = new Rewrite(subject);
        String rewritten;
        CURRENT.set(rewrite);
        try {
            rewritten = multi ? parserMulti(sql, ms.getId()) : parserSingle(sql, ms.getId());
        } finally {
            CURRENT.remove();
        }
        if (rewrite.parameters.isEmpty() && rewritten.equals(sql)) return;
        bind(rewritten, rewrite.parameters, mpBs, boundSql, ms.getConfiguration());
    }

    // 粗筛：不含任何资源表名的语句不必解析；含表名但实际不涉及（如列名相同）时解析后不会注入条件。
    private boolean mentionsResourceTable(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        return permission.byTable().keySet().stream().anyMatch(lower::contains);
    }

    @Override
    protected void processSelect(Select select, int index, String sql, Object obj) {
        String whereSegment = (String) obj;
        processSelectBody(select, whereSegment);
        if (select.getWithItemsList() != null) {
            select.getWithItemsList().forEach(item -> processSelectBody(item.getSelect(), whereSegment));
        }
    }

    @Override
    protected void processUpdate(Update update, int index, String sql, Object obj) {
        Expression where = andExpression(update.getTable(), update.getWhere(), (String) obj);
        if (where != null) update.setWhere(where);
    }

    @Override
    protected void processDelete(Delete delete, int index, String sql, Object obj) {
        Expression where = andExpression(delete.getTable(), delete.getWhere(), (String) obj);
        if (where != null) delete.setWhere(where);
    }

    @Override
    public Expression buildTableExpression(Table table, Expression where, String whereSegment) {
        DataResourceDefinition definition = permission.byTable().get(DataPermission.normalizeTable(table.getName()));
        if (definition == null) return null;
        Rewrite rewrite = CURRENT.get();
        String qualifier = table.getAlias() != null ? table.getAlias().getName() : table.getName();
        SqlPredicate predicate = permission.compile(rewrite.subject, definition, qualifier);
        var marked = new StringBuilder("(");
        int next = 0;
        for (char c : predicate.sql().toCharArray()) {
            // 编译结果中除占位符外没有任何 ?：值一律参数化，列名经过校验。
            if (c == '?') {
                marked.append(MARKER).append(rewrite.parameters.size());
                rewrite.parameters.add(predicate.parameters().get(next++));
            } else {
                marked.append(c);
            }
        }
        try {
            return CCJSqlParserUtil.parseCondExpression(marked.append(')').toString());
        } catch (JSQLParserException e) {
            throw new IllegalStateException("Cannot parse compiled Data Policy: " + predicate.sql(), e);
        }
    }

    /**
     * 把改写后 SQL 中的标记替换为 {@code ?}，并按出现顺序在原有参数映射中插入策略参数。
     *
     * @throws IllegalStateException 原有占位符数量与参数映射对不上，无法安全绑定
     */
    static void bind(
            String sql,
            List<SqlParameter> parameters,
            PluginUtils.MPBoundSql mpBs,
            BoundSql boundSql,
            Configuration configuration) {
        List<ParameterMapping> original = mpBs.parameterMappings();
        var mappings = new ArrayList<ParameterMapping>(original.size() + parameters.size());
        var out = new StringBuilder(sql.length());
        int originalIndex = 0;
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                out.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                out.append(c);
            } else if (c == '$' && dollarQuoteEnd(sql, i) > i) {
                // PostgreSQL 的 $tag$...$tag$ 字符串原样保留，其中的 ? 不是占位符
                int end = dollarQuoteEnd(sql, i);
                out.append(sql, i, end);
                i = end - 1;
            } else if (c == '?') {
                if (originalIndex >= original.size()) throw mismatch(sql);
                mappings.add(original.get(originalIndex++));
                out.append('?');
            } else if (sql.startsWith(MARKER, i)) {
                int end = i + MARKER.length();
                while (end < sql.length() && Character.isDigit(sql.charAt(end))) end++;
                int index = Integer.parseInt(sql.substring(i + MARKER.length(), end));
                String property = PROPERTY + index;
                boundSql.setAdditionalParameter(property, parameters.get(index));
                mappings.add(new ParameterMapping.Builder(configuration, property, TYPE_HANDLER).build());
                out.append('?');
                i = end - 1;
            } else {
                out.append(c);
            }
        }
        if (originalIndex != original.size()) throw mismatch(sql);
        mpBs.sql(out.toString());
        mpBs.parameterMappings(mappings);
    }

    /**
     * 从 {@code start} 处的 {@code $} 开始的 dollar 引号字符串的结束位置（不含）；不是 dollar 引号或没有闭合时返回 -1。
     * 标签为空或标识符（不以数字开头），与 PostgreSQL 一致。
     */
    private static int dollarQuoteEnd(String sql, int start) {
        int tagEnd = start + 1;
        while (tagEnd < sql.length()
                && (Character.isLetter(sql.charAt(tagEnd))
                        || sql.charAt(tagEnd) == '_'
                        || (tagEnd > start + 1 && Character.isDigit(sql.charAt(tagEnd))))) {
            tagEnd++;
        }
        if (tagEnd >= sql.length() || sql.charAt(tagEnd) != '$') return -1;
        String tag = sql.substring(start, tagEnd + 1);
        int close = sql.indexOf(tag, tagEnd + 1);
        return close < 0 ? -1 : close + tag.length();
    }

    private static IllegalStateException mismatch(String sql) {
        return new IllegalStateException(
                "Placeholders do not match parameter mappings after Data Policy rewrite: " + sql);
    }

    /** 一条语句改写过程中收集的策略参数，按标记编号排列。 */
    private static final class Rewrite {
        private final DataPermission.Subject subject;
        private final List<SqlParameter> parameters = new ArrayList<>();

        Rewrite(DataPermission.Subject subject) {
            this.subject = subject;
        }
    }
}
