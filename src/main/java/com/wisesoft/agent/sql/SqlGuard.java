package com.wisesoft.agent.sql;

import com.wisesoft.agent.config.AgentProperties;
import com.wisesoft.agent.semantic.ForbiddenJoin;
import com.wisesoft.agent.semantic.JoinDef;
import com.wisesoft.agent.semantic.SemanticModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectVisitor;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQL 安全守卫：把模型生成的 SQL 解析成语法树后做白名单校验。
 * <p>
 * 不用正则的原因：正则挡不住注释注入（-- 注释掉后面的校验逻辑）、
 * 大小写变体、嵌套子查询。AST 校验才是语法层面的可靠手段。
 * <p>
 * 校验规则：
 * <ol>
 *   <li>只允许 SELECT（拒绝 INSERT/UPDATE/DELETE/DDL/INTO OUTFILE）</li>
 *   <li>表名必须在语义层登记过</li>
 *   <li>列名必须在对应表的语义层登记过；无表前缀的列必须无歧义</li>
 *   <li>join 条件必须在 joins.yml 登记过，且方向正确（1:n 反接会放大金额）</li>
 *   <li>禁止连接（joins.yml 的 forbiddenJoins）给出可解释的拒绝原因</li>
 *   <li>危险函数黑名单（SLEEP/BENCHMARK 等拖慢或探测类函数）</li>
 *   <li>顶层无 LIMIT 时自动注入行数上限</li>
 * </ol>
 * 校验失败抛 {@link SqlValidationException}，携带逐条修复建议回灌给模型重试。
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlGuard {

    /** 函数黑名单：SELECT 里也不允许出现，防探测与拖慢 */
    private static final Set<String> BANNED_FUNCTIONS = Set.of(
            "sleep", "benchmark", "load_file", "get_lock", "release_lock", "master_pos_wait");

    private final SemanticModel model;
    private final AgentProperties properties;

    /** 校验结果：修正后的 SQL（可能注入了 LIMIT） */
    public record GuardResult(String guardedSql, boolean limitInjected) {
    }

    public GuardResult validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new SqlValidationException(SqlValidationException.Stage.GUARD,
                    "SQL 不能为空", List.of("请提供一条完整的 SELECT 语句"));
        }

        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            throw new SqlValidationException(SqlValidationException.Stage.PARSE,
                    "SQL 语法解析失败，请检查语句是否有拼写错误（尤其是列名、函数名、引号配对）",
                    List.of("MySQL 方言要求完整的 SELECT 语句，不要加结尾分号",
                            "检查字符串是否用了单引号且成对"));
        }

        if (!(statement instanceof Select select)) {
            throw new SqlValidationException(SqlValidationException.Stage.GUARD,
                    "只允许 SELECT 查询语句",
                    List.of("本系统只支持只读查询，请只写 SELECT"));
        }

        List<String> errors = new ArrayList<>();
        select.accept(visitor(errors), null);

        if (!errors.isEmpty()) {
            throw new SqlValidationException(SqlValidationException.Stage.GUARD,
                    "SQL 未通过安全校验（" + errors.size() + " 处问题）", errors);
        }

        // 顶层无 LIMIT 时注入行数上限，防止全表扫描撑爆内存
        boolean injected = false;
        if (select instanceof PlainSelect ps && ps.getLimit() == null) {
            Limit limit = new Limit();
            limit.setRowCount(new LongValue(maxRows()));
            ps.setLimit(limit);
            injected = true;
        }
        return new GuardResult(select.toString(), injected);
    }

    /** 递归遍历所有 SELECT 子查询的 visitor */
    private SelectVisitor<Void> visitor(List<String> errors) {
        return new SelectVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(PlainSelect plainSelect, S context) {
                validatePlainSelect(plainSelect, errors);
                return null;
            }

            @Override
            public <S> Void visit(SetOperationList sol, S context) {
                sol.getSelects().forEach(s -> s.accept(this, null));
                return null;
            }

            @Override
            public <S> Void visit(WithItem<?> withItem, S context) {
                ParenthesedSelect ps = withItem.getSelect();
                if (ps != null) {
                    ps.accept(this, null);
                }
                return null;
            }
        };
    }

    private void validatePlainSelect(PlainSelect ps, List<String> errors) {
        // SELECT ... INTO OUTFILE 会写文件，直接拒绝
        if (ps.getIntoTables() != null && !ps.getIntoTables().isEmpty()) {
            errors.add("不允许 SELECT INTO 文件导出");
        }

        // 别名（小写）→ 真实表名（小写）
        Map<String, String> aliasToTable = new LinkedHashMap<>();
        // 子查询别名 → 其 SELECT 输出列名（外层引用子查询列时校验）
        Map<String, Set<String>> subqueryOutputCols = new LinkedHashMap<>();
        // 本次查询涉及的真实表名
        Set<String> tableSet = new LinkedHashSet<>();

        collectFromItem(ps.getFromItem(), aliasToTable, subqueryOutputCols, tableSet, errors);
        // join 的右表同样要注册进别名映射，否则 on 条件里的列前缀解析不出来
        if (ps.getJoins() != null) {
            for (Join join : ps.getJoins()) {
                collectFromItem(join.getRightItem(), aliasToTable, subqueryOutputCols, tableSet, errors);
            }
        }

        // 收集所有需要校验的列与函数；同时记录 SELECT 别名（ORDER BY 引用别名要放行）
        ColumnCollector collector = new ColumnCollector();
        Set<String> selectAliases = new LinkedHashSet<>();
        for (SelectItem<?> item : ps.getSelectItems()) {
            Expression expr = item.getExpression();
            if (expr != null) {
                expr.accept(collector, null);
            }
            // AllColumns(*) / AllTableColumns(t.*) 不校验列，表已白名单
            String alias = item.getAlias() == null ? null : item.getAlias().getName();
            if (alias != null) {
                selectAliases.add(alias.toLowerCase(Locale.ROOT));
            }
        }
        Expression where = ps.getWhere();
        if (where != null) {
            where.accept(collector, null);
        }
        if (ps.getGroupBy() != null && ps.getGroupBy().getGroupByExpressionList() != null) {
            ps.getGroupBy().getGroupByExpressionList().accept(collector, null);
        }
        Expression having = ps.getHaving();
        if (having != null) {
            having.accept(collector, null);
        }
        List<OrderByElement> orderBy = ps.getOrderByElements();
        if (orderBy != null) {
            orderBy.forEach(o -> o.getExpression().accept(collector, null));
        }

        // 校验列引用
        for (Column col : collector.columns) {
            // ORDER BY 里的别名列（如 ORDER BY gmv）放行：它引用的是已校验的 SELECT 项
            if (col.getTable() == null && selectAliases.contains(col.getColumnName().toLowerCase(Locale.ROOT))) {
                continue;
            }
            validateColumn(col, aliasToTable, subqueryOutputCols, tableSet, errors);
        }
        // 校验函数黑名单
        for (Function f : collector.functions) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (BANNED_FUNCTIONS.contains(name)) {
                errors.add("函数 " + f.getName() + " 被禁止使用");
            }
        }

        // 校验 join
        validateJoins(ps, aliasToTable, subqueryOutputCols, tableSet, errors);
    }

    /** 收集 FROM 项（表或子查询），子查询递归校验并登记其输出列 */
    private void collectFromItem(FromItem item, Map<String, String> aliasToTable,
                                 Map<String, Set<String>> subqueryOutputCols,
                                 Set<String> tableSet, List<String> errors) {
        if (item instanceof Table t) {
            registerTable(t, aliasToTable, tableSet, errors);
        } else if (item instanceof ParenthesedSelect sub) {
            Select inner = sub.getSelect();
            if (inner instanceof PlainSelect innerPs) {
                Set<String> outCols = new LinkedHashSet<>();
                for (SelectItem<?> si : innerPs.getSelectItems()) {
                    String alias = si.getAlias() == null ? null : si.getAlias().getName();
                    if (alias != null) {
                        outCols.add(alias.toLowerCase(Locale.ROOT));
                    } else if (si.getExpression() instanceof Column c) {
                        outCols.add(c.getColumnName().toLowerCase(Locale.ROOT));
                    }
                }
                String alias = sub.getAlias() != null
                        ? sub.getAlias().getName().toLowerCase(Locale.ROOT) : null;
                if (alias != null) {
                    subqueryOutputCols.put(alias, outCols);
                }
            }
            inner.accept(visitor(errors), null);
        }
    }

    private void registerTable(Table t, Map<String, String> aliasToTable,
                               Set<String> tableSet, List<String> errors) {
        String name = t.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        String alias = t.getAlias() != null
                ? t.getAlias().getName().toLowerCase(Locale.ROOT) : lower;
        if (model.table(lower) == null) {
            errors.add("表 " + name + " 不在可查询范围内。可用表：" + model.allTableNames());
            return;
        }
        aliasToTable.put(alias, lower);
        tableSet.add(lower);
    }

    /** 校验列引用：表前缀必须在场且列存在；无前缀列必须无歧义 */
    private void validateColumn(Column col, Map<String, String> aliasToTable,
                                Map<String, Set<String>> subqueryOutputCols,
                                Set<String> tableSet, List<String> errors) {
        String colName = col.getColumnName().toLowerCase(Locale.ROOT);
        Table qualifier = col.getTable();
        if (qualifier == null || qualifier.getName() == null || qualifier.getName().isBlank()) {
            // 无前缀列：在 FROM 表集中找唯一匹配
            List<String> owners = new ArrayList<>();
            for (String table : tableSet) {
                if (model.table(table).column(colName) != null) {
                    owners.add(table);
                }
            }
            if (owners.isEmpty()) {
                errors.add("列 " + col.getColumnName() + " 不存在于任何已查询的表（" + tableSet + "）中");
            } else if (owners.size() > 1) {
                errors.add("列 " + col.getColumnName() + " 在表 " + owners
                        + " 中都存在，有歧义，请加表名前缀，如 " + owners.get(0) + "." + col.getColumnName());
            }
            return;
        }

        String rawTable = qualifier.getName();
        String realTable = aliasToTable.get(rawTable.toLowerCase(Locale.ROOT));
        if (realTable == null) {
            // 子查询别名：校验列名是否在子查询的输出列里（内容已递归校验过）
            Set<String> outCols = subqueryOutputCols.get(rawTable.toLowerCase(Locale.ROOT));
            if (outCols != null) {
                if (!outCols.contains(colName)) {
                    errors.add("列 " + rawTable + "." + col.getColumnName()
                            + " 不在该子查询的输出列（" + outCols + "）中");
                }
                return;
            }
            errors.add("列前缀 " + rawTable + " 无法解析为已查询的表。如果这是子查询别名，请改用真实表名加前缀");
            return;
        }
        if (model.table(realTable).column(colName) == null) {
            errors.add("列 " + realTable + "." + col.getColumnName() + " 不存在。"
                    + realTable + " 的可选列：" + model.columnNames(realTable));
        }
    }

    private void validateJoins(PlainSelect ps, Map<String, String> aliasToTable,
                               Map<String, Set<String>> subqueryOutputCols,
                               Set<String> tableSet, List<String> errors) {
        if (ps.getJoins() == null) {
            return;
        }
        // 第一个 join 的左表是 fromItem；后续 join 的左表是前一个 join 的右表
        FromItem left = ps.getFromItem();
        for (Join join : ps.getJoins()) {
            if (join.isCross()) {
                errors.add("不允许 CROSS JOIN，请使用带 on 条件的 INNER/LEFT JOIN");
                left = join.getRightItem();
                continue;
            }
            FromItem rightItem = join.getRightItem();
            String leftTable = tableNameOf(left, aliasToTable);
            String rightTable = tableNameOf(rightItem, aliasToTable);
            if (leftTable == null || rightTable == null) {
                errors.add("join 的某一侧不是普通表，本系统只支持表之间的连接");
                left = rightItem;
                continue;
            }

            // 连接条件白名单：先解析成真实表名形式再比对
            Expression onExpr = join.getOnExpression();
            if (onExpr == null) {
                errors.add("join 必须带 on 条件");
                left = rightItem;
                continue;
            }
            onExpr.accept(new ExpressionVisitorAdapter<Void>() {
                @Override
                public <S> Void visit(Column col, S context) {
                    validateColumn(col, aliasToTable, subqueryOutputCols, tableSet, errors);
                    return null;
                }
            }, null);

            // 等值连接对集合（真实表名形式，如 "fact_order.order_id=fact_order_item.order_id"）
            List<String> eqPairs = equalPairs(onExpr, aliasToTable, errors);

            if (eqPairs.isEmpty() && !errors.isEmpty()) {
                left = rightItem;
                continue;
            }
            JoinDef approved = null;
            for (String pair : eqPairs) {
                JoinDef hit = model.approvedJoin(pair);
                if (hit != null) {
                    approved = hit;
                    break;
                }
            }

            if (approved == null) {
                // 没有批准的连接：先看是否命中禁止连接（给出可解释的原因），否则给出已批准的列表
                ForbiddenJoin forbidden = model.forbiddenJoin(leftTable, rightTable);
                if (forbidden != null) {
                    String msg = "表 " + leftTable + " 与 " + rightTable + " 不允许直接连接："
                            + forbidden.getReason();
                    if (forbidden.getViaOnly() != null) {
                        JoinDef via = model.joinById(forbidden.getViaOnly());
                        if (via != null) {
                            msg += " 应使用已批准的连接：" + via.getOn();
                        }
                    }
                    errors.add(msg);
                } else {
                    errors.add("连接条件 " + onExpr + " 不在批准列表。"
                            + "该表对已批准的连接：" + approvedJoinsBetween(leftTable, rightTable));
                }
            } else {
                // 方向检查：approved 的 left/right 是语义层声明的方向（n:1 的 1 在右）
                boolean directionOk = approved.getLeft().equalsIgnoreCase(leftTable)
                        && approved.getRight().equalsIgnoreCase(rightTable);
                if (!directionOk) {
                    errors.add("连接方向反了：" + leftTable + " " + joinType(join) + " JOIN " + rightTable
                            + " 会把明细放大。批准的方向是 " + approved.getLeft() + " → " + approved.getRight()
                            + "（" + approved.getCardinality() + "），请交换主从表。"
                            + "原因：" + approved.getDescription());
                }
            }
            left = rightItem;
        }
    }

    /**
     * 把 on 条件拆成等值连接对，并把列前缀从别名解析回真实表名。
     * 返回形如 "fact_order.order_id=fact_order_item.order_id" 的规范化对。
     * on 条件里出现非「列=列」的表达式时记入 errors 并跳过该对。
     */
    private List<String> equalPairs(Expression onExpr, Map<String, String> aliasToTable,
                                    List<String> errors) {
        List<String> pairs = new ArrayList<>();
        onExpr.accept(new ExpressionVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(EqualsTo eq, S context) {
                String l = resolveColumn(eq.getLeftExpression(), aliasToTable);
                String r = resolveColumn(eq.getRightExpression(), aliasToTable);
                if (l == null || r == null) {
                    errors.add("连接条件必须是 表.列 = 表.列 的等值连接，当前包含复杂表达式：" + onExpr);
                } else {
                    pairs.add(l.compareTo(r) <= 0 ? l + "=" + r : r + "=" + l);
                }
                return null;
            }
        }, null);
        return pairs;
    }

    /** 把表达式解析成 真实表名.列名（经别名映射）；非列引用返回 null */
    private String resolveColumn(Expression expr, Map<String, String> aliasToTable) {
        if (!(expr instanceof Column col)) {
            return null;
        }
        Table qualifier = col.getTable();
        if (qualifier == null || qualifier.getName() == null || qualifier.getName().isBlank()) {
            return null;
        }
        String real = aliasToTable.get(qualifier.getName().toLowerCase(Locale.ROOT));
        return real == null ? null : real + "." + col.getColumnName().toLowerCase(Locale.ROOT);
    }

    /** 取 FROM 项的真实表名（经别名映射），子查询返回 null */
    private String tableNameOf(FromItem item, Map<String, String> aliasToTable) {
        if (item instanceof Table t) {
            String name = t.getName().toLowerCase(Locale.ROOT);
            String alias = t.getAlias() != null
                    ? t.getAlias().getName().toLowerCase(Locale.ROOT) : name;
            return aliasToTable.getOrDefault(alias, name);
        }
        return null;
    }

    private String joinType(Join join) {
        if (join.isLeft()) {
            return "LEFT";
        }
        if (join.isRight()) {
            return "RIGHT";
        }
        return "INNER";
    }

    /** 列出两张表之间已批准的所有连接，给模型当修复提示 */
    private String approvedJoinsBetween(String t1, String t2) {
        List<String> found = new ArrayList<>();
        for (JoinDef j : model.getJoins().values()) {
            if ((j.getLeft().equalsIgnoreCase(t1) && j.getRight().equalsIgnoreCase(t2))
                    || (j.getLeft().equalsIgnoreCase(t2) && j.getRight().equalsIgnoreCase(t1))) {
                found.add(j.getId() + ": " + j.getOn() + "（方向 " + j.getLeft() + " → " + j.getRight() + "）");
            }
        }
        return found.isEmpty() ? "无。这两张表之间没有批准的直接连接，请调整查询结构"
                : String.join("；", found);
    }

    private int maxRows() {
        return properties.getSqlExec().getMaxRows();
    }

    /** 收集一条 SELECT 里所有列引用与函数调用 */
    private static class ColumnCollector extends ExpressionVisitorAdapter<Void> {
        final List<Column> columns = new ArrayList<>();
        final List<Function> functions = new ArrayList<>();

        @Override
        public <S> Void visit(Column column, S context) {
            columns.add(column);
            return null;
        }

        @Override
        public <S> Void visit(Function function, S context) {
            functions.add(function);
            return super.visit(function, context);
        }
    }
}
