package com.wisesoft.agent.sql;

import com.wisesoft.agent.config.AgentProperties;
import com.wisesoft.agent.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务库查询执行器。
 * <p>
 * 两条执行路径，安全模型完全不同：
 * <ul>
 *   <li>{@link #executeTemplate} —— 模板 SQL + 命名参数绑定。SQL 是我们自己写死的，
 *       参数走 JDBC 绑定不做拼接，天然免疫注入。</li>
 *   <li>{@link #executeGenerated} —— 模型生成的 SQL。**必须**先过 SqlGuard，
 *       这个方法本身不做校验，调用方有责任先校验。</li>
 * </ul>
 * 两条路径共用的兜底：只读账号、连接级 readOnly、查询超时、行数上限。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class SqlExecutor {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ConfigService configService;
    private final AgentProperties properties;

    public SqlExecutor(@Qualifier("businessJdbcTemplate") JdbcTemplate jdbc,
                       @Qualifier("businessNamedJdbcTemplate") NamedParameterJdbcTemplate namedJdbc,
                       ConfigService configService,
                       AgentProperties properties) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.configService = configService;
        this.properties = properties;
    }

    /** 执行模板 SQL（命名参数绑定） */
    public QueryResult executeTemplate(String sql, Map<String, Object> params) {
        long start = System.currentTimeMillis();
        int maxRows = maxRows();
        try {
            List<String> columns = new ArrayList<>();
            List<Map<String, Object>> rows = namedJdbc.query(sql, new MapSqlParameterSource(params),
                    rs -> {
                        readColumns(rs.getMetaData(), columns);
                        return readRows(rs, columns, maxRows);
                    });
            return build(columns, rows, sql, start, maxRows);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new SqlValidationException(SqlValidationException.Stage.EXECUTE,
                    rootMessage(e), List.of("模板 SQL 执行失败，通常是参数值不合法，请检查参数格式"));
        }
    }

    /**
     * 执行模型生成的 SQL。
     * <p>
     * <b>调用方必须先过 SqlGuard。</b>这里不重复校验，是为了让职责边界清晰：
     * 校验归 SqlGuard，执行归这里。但只读账号、超时、行数上限这几层
     * 兜底是无条件生效的。
     */
    public QueryResult executeGenerated(String sql) {
        long start = System.currentTimeMillis();
        int maxRows = maxRows();
        try {
            List<String> columns = new ArrayList<>();
            List<Map<String, Object>> rows = jdbc.query(sql, rs -> {
                readColumns(rs.getMetaData(), columns);
                return readRows(rs, columns, maxRows);
            });
            return build(columns, rows, sql, start, maxRows);
        } catch (org.springframework.dao.DataAccessException e) {
            // 数据库原始报错原样带给模型：列名拼错、函数用错这类问题，
            // MySQL 的报错信息本身就是最有效的修复线索
            throw new SqlValidationException(SqlValidationException.Stage.EXECUTE,
                    rootMessage(e), List.of("这是数据库返回的原始错误，请据此定位并修正 SQL"));
        }
    }

    /**
     * EXPLAIN 干跑：不取数据，只让数据库检查这条 SQL 能不能执行。
     * 比直接跑一遍便宜得多，能提前拦掉列名错误、语法问题、表不存在。
     */
    public void explainDryRun(String sql) {
        if (!configService.getBoolean("sql.explainDryRun")) {
            return;
        }
        try {
            jdbc.query("EXPLAIN " + sql, rs -> {
                // 只关心能不能通过，不读内容
            });
        } catch (org.springframework.dao.DataAccessException e) {
            throw new SqlValidationException(SqlValidationException.Stage.DRY_RUN,
                    rootMessage(e), List.of("执行计划检查未通过，说明 SQL 本身有问题（列名/表名/语法），请修正"));
        }
    }

    private QueryResult build(List<String> columns, List<Map<String, Object>> rows,
                              String sql, long start, int maxRows) {
        boolean truncated = rows.size() >= maxRows;
        long elapsed = System.currentTimeMillis() - start;
        if (truncated) {
            log.warn("[FAIL-LOUD] 查询结果达到行数上限 {} 被截断，结果可能不完整: {}", maxRows, oneLine(sql));
        }
        log.debug("[SQL] {}ms {}行 {}", elapsed, rows.size(), oneLine(sql));
        return new QueryResult(columns, rows, sql, elapsed, truncated);
    }

    private void readColumns(ResultSetMetaData meta, List<String> columns) throws java.sql.SQLException {
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            // getColumnLabel 而非 getColumnName：SQL 里写了中文别名要取到别名
            columns.add(meta.getColumnLabel(i));
        }
    }

    private List<Map<String, Object>> readRows(java.sql.ResultSet rs, List<String> columns, int maxRows)
            throws java.sql.SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next() && rows.size() < maxRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                row.put(columns.get(i), rs.getObject(i + 1));
            }
            rows.add(row);
        }
        return rows;
    }

    private int maxRows() {
        return configService.getInt("sql.maxRows", properties.getSqlExec().getMaxRows());
    }

    /** 取最内层的原始报错，Spring 包装的那几层对模型没有价值 */
    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null ? e.toString() : msg;
    }

    private String oneLine(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
    }
}
