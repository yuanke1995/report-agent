package com.wisesoft.agent.semantic;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义层校验：把 YAML 里写的东西和真实库对一遍。
 * <p>
 * 语义层是人工维护的，而库结构会变。两者一旦漂移，症状是 SQL 生成
 * 莫名其妙地失败或算错，且很难归因。所以在启动时就对齐：
 * <ul>
 *   <li><b>ERROR（拒绝启动）</b>：YAML 引用了库里不存在的表/列、join 引用了不存在的
 *       表、指标引用了不存在的 join、模板 SQL 的命名参数与声明不一致。
 *       这些必然导致运行期出错，早失败比晚失败好。</li>
 *   <li><b>WARN（放行但告警）</b>：库里存在但 YAML 没描述的列。不影响正确性，
 *       但这些列对 Schema Linking 是盲区，模型看不见就用不上。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class SemanticModelValidator {

    /** 模板 SQL 里的命名参数 :name（排除 ::cast 这类写法） */
    private static final Pattern NAMED_PARAM = Pattern.compile("(?<!:):([A-Za-z][A-Za-z0-9_]*)");

    private final JdbcTemplate businessJdbc;
    private final String businessSchema;

    public void validate(SemanticModel model) {
        Map<String, Set<String>> dbSchema = readDbSchema();
        // 用 Set 去重：同一张表缺失会同时触发表校验和多条 join 校验，
        // 重复十几行同样的话只会淹没真正有用的信息
        Set<String> errors = new LinkedHashSet<>();
        Set<String> warnings = new LinkedHashSet<>();

        validateTables(model, dbSchema, errors, warnings);
        validateJoins(model, dbSchema, errors);
        validateMetrics(model, errors);
        validateTemplates(model, errors);

        warnings.forEach(w -> log.warn("[语义层] {}", w));

        if (!errors.isEmpty()) {
            String detail = String.join("\n  - ", errors);
            throw new IllegalStateException(
                    "语义层与数据库 " + businessSchema + " 不一致，服务拒绝启动：\n  - " + detail);
        }
        log.info("[语义层] 校验通过，与数据库 {} 对齐（{} 项告警）", businessSchema, warnings.size());
    }

    /** 读取业务库真实表结构：表名（小写）→ 列名集合（小写） */
    private Map<String, Set<String>> readDbSchema() {
        Map<String, Set<String>> schema = new LinkedHashMap<>();
        List<Map<String, Object>> rows = businessJdbc.queryForList(
                "SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ?",
                businessSchema);
        for (Map<String, Object> row : rows) {
            String t = String.valueOf(row.get("TABLE_NAME")).toLowerCase(Locale.ROOT);
            String c = String.valueOf(row.get("COLUMN_NAME")).toLowerCase(Locale.ROOT);
            schema.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(c);
        }
        if (schema.isEmpty()) {
            throw new IllegalStateException(
                    "业务库 " + businessSchema + " 中读不到任何表，请先执行 db/01_business_schema.sql");
        }
        return schema;
    }

    private void validateTables(SemanticModel model, Map<String, Set<String>> db,
                                Set<String> errors, Set<String> warnings) {
        for (TableDef t : model.getTables().values()) {
            String tn = t.getTable().toLowerCase(Locale.ROOT);
            Set<String> dbCols = db.get(tn);
            if (dbCols == null) {
                errors.add("表 " + t.getTable() + " 在库中不存在");
                continue;
            }
            Set<String> yamlCols = new LinkedHashSet<>();
            for (ColumnDef c : t.getColumns()) {
                String cn = c.getName().toLowerCase(Locale.ROOT);
                yamlCols.add(cn);
                if (!dbCols.contains(cn)) {
                    errors.add("列 " + t.getTable() + "." + c.getName() + " 在库中不存在");
                }
            }
            for (String dbCol : dbCols) {
                if (!yamlCols.contains(dbCol)) {
                    warnings.add("列 " + t.getTable() + "." + dbCol
                            + " 未在语义层描述，Schema Linking 无法召回它");
                }
            }
            long timeCols = t.getColumns().stream().filter(ColumnDef::isPrimaryTimeColumn).count();
            if (timeCols > 1) {
                errors.add("表 " + t.getTable() + " 声明了 " + timeCols + " 个主时间列，只能有一个");
            }
        }
        for (String dbTable : db.keySet()) {
            if (model.table(dbTable) == null) {
                warnings.add("表 " + dbTable + " 未在语义层描述，Agent 看不到这张表");
            }
        }
    }

    private void validateJoins(SemanticModel model, Map<String, Set<String>> db, Set<String> errors) {
        for (JoinDef j : model.getJoins().values()) {
            checkJoinTable(j.getId(), j.getLeft(), db, errors);
            checkJoinTable(j.getId(), j.getRight(), db, errors);
            if (j.getOn() == null || !j.getOn().contains("=")) {
                errors.add("join " + j.getId() + " 的 on 条件不合法: " + j.getOn());
                continue;
            }
            for (String side : j.getOn().split("=")) {
                String ref = side.trim();
                if (!ref.contains(".")) {
                    errors.add("join " + j.getId() + " 的 on 条件必须用 表名.列名 限定: " + ref);
                    continue;
                }
                String[] parts = ref.split("\\.", 2);
                String tbl = parts[0].trim().toLowerCase(Locale.ROOT);
                String col = parts[1].trim().toLowerCase(Locale.ROOT);
                Set<String> cols = db.get(tbl);
                if (cols == null) {
                    errors.add("join " + j.getId() + " 引用了不存在的表: " + tbl);
                } else if (!cols.contains(col)) {
                    errors.add("join " + j.getId() + " 引用了不存在的列: " + tbl + "." + col);
                }
            }
        }
        for (ForbiddenJoin f : model.getForbiddenJoins()) {
            if (f.getViaOnly() != null && model.joinById(f.getViaOnly()) == null) {
                errors.add("forbiddenJoin " + f.getLeft() + "↔" + f.getRight()
                        + " 的 viaOnly 指向不存在的 join: " + f.getViaOnly());
            }
        }
    }

    private void checkJoinTable(String joinId, String table, Map<String, Set<String>> db, Set<String> errors) {
        if (table == null || !db.containsKey(table.toLowerCase(Locale.ROOT))) {
            errors.add("join " + joinId + " 引用了不存在的表: " + table);
        }
    }

    private void validateMetrics(SemanticModel model, Set<String> errors) {
        for (MetricDef m : model.getMetrics().values()) {
            if (m.getBaseTable() == null || model.table(m.getBaseTable()) == null) {
                errors.add("指标 " + m.getName() + " 的 baseTable 不在语义层中: " + m.getBaseTable());
            }
            if (m.getExpression() == null || m.getExpression().isBlank()) {
                errors.add("指标 " + m.getName() + " 缺少 expression");
            }
            for (String jid : m.getRequiredJoins()) {
                if (model.joinById(jid) == null) {
                    errors.add("指标 " + m.getName() + " 的 requiredJoins 指向不存在的 join: " + jid);
                }
            }
        }
    }

    private void validateTemplates(SemanticModel model, Set<String> errors) {
        for (ReportTemplate t : model.getTemplates().values()) {
            if (t.getSql() == null || t.getSql().isBlank()) {
                errors.add("模板 " + t.getId() + " 缺少 sql");
                continue;
            }
            Set<String> declared = new LinkedHashSet<>();
            for (TemplateParam p : t.getParams()) {
                if (!declared.add(p.getName())) {
                    errors.add("模板 " + t.getId() + " 参数重复声明: " + p.getName());
                }
                if (p.getType() == TemplateParam.ParamType.ENUM && p.getOptions().isEmpty()) {
                    errors.add("模板 " + t.getId() + " 的枚举参数 " + p.getName() + " 未提供 options");
                }
            }
            Set<String> used = new LinkedHashSet<>();
            Matcher m = NAMED_PARAM.matcher(t.getSql());
            while (m.find()) {
                used.add(m.group(1));
            }
            for (String u : used) {
                if (!declared.contains(u)) {
                    errors.add("模板 " + t.getId() + " 的 SQL 用到了未声明的参数 :" + u);
                }
            }
            for (String d : declared) {
                if (!used.contains(d)) {
                    errors.add("模板 " + t.getId() + " 声明了参数 " + d + " 但 SQL 中未使用");
                }
            }
            if (t.getChart() != null && t.getChart().getType() == null) {
                errors.add("模板 " + t.getId() + " 的 chart 缺少 type");
            }
        }
    }
}
