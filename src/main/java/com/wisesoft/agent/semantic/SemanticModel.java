package com.wisesoft.agent.semantic;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 语义层聚合根：加载完成后不可变，全局单例。
 * <p>
 * 对外提供三类能力：
 * 1. 按名字精确查（表/列/指标/模板/join）—— SqlGuard 校验用
 * 2. 按同义词模糊查 —— Schema Linking 召回用
 * 3. 渲染成 prompt 片段 —— SQL 生成时注入用
 *
 * @author yuanke
 */
@Getter
public class SemanticModel {

    private final Map<String, TableDef> tables;
    private final Map<String, MetricDef> metrics;
    private final Map<String, JoinDef> joins;
    private final Map<String, ReportTemplate> templates;
    private final List<ForbiddenJoin> forbiddenJoins;

    /** 归一化的 on 条件 → join 定义，SqlGuard 比对连接白名单用 */
    private final Map<String, JoinDef> joinsByNormalizedOn;

    /** 小写同义词 → 表名，Schema Linking 用 */
    private final Map<String, String> tableSynonymIndex;

    /** 小写同义词 → 指标名 */
    private final Map<String, String> metricSynonymIndex;

    public SemanticModel(List<TableDef> tableList,
                         List<MetricDef> metricList,
                         List<JoinDef> joinList,
                         List<ForbiddenJoin> forbiddenList,
                         List<ReportTemplate> templateList) {
        Map<String, TableDef> t = new LinkedHashMap<>();
        for (TableDef def : tableList) {
            Map<String, ColumnDef> idx = new LinkedHashMap<>();
            for (ColumnDef c : def.getColumns()) {
                idx.put(c.getName().toLowerCase(Locale.ROOT), c);
            }
            def.setColumnIndex(idx);
            t.put(def.getTable().toLowerCase(Locale.ROOT), def);
        }
        this.tables = Map.copyOf(t);

        Map<String, MetricDef> m = new LinkedHashMap<>();
        metricList.forEach(x -> m.put(x.getName().toLowerCase(Locale.ROOT), x));
        this.metrics = Map.copyOf(m);

        Map<String, JoinDef> j = new LinkedHashMap<>();
        Map<String, JoinDef> jon = new LinkedHashMap<>();
        for (JoinDef def : joinList) {
            j.put(def.getId().toLowerCase(Locale.ROOT), def);
            jon.put(def.normalizedOn(), def);
        }
        this.joins = Map.copyOf(j);
        this.joinsByNormalizedOn = Map.copyOf(jon);

        Map<String, ReportTemplate> tpl = new LinkedHashMap<>();
        templateList.forEach(x -> tpl.put(x.getId().toLowerCase(Locale.ROOT), x));
        this.templates = Map.copyOf(tpl);

        this.forbiddenJoins = List.copyOf(forbiddenList);

        Map<String, String> ts = new LinkedHashMap<>();
        for (TableDef def : tableList) {
            ts.put(def.getTable().toLowerCase(Locale.ROOT), def.getTable());
            if (def.getDisplayName() != null) {
                ts.put(def.getDisplayName().toLowerCase(Locale.ROOT), def.getTable());
            }
            def.getSynonyms().forEach(s -> ts.put(s.toLowerCase(Locale.ROOT), def.getTable()));
        }
        this.tableSynonymIndex = Map.copyOf(ts);

        Map<String, String> ms = new LinkedHashMap<>();
        for (MetricDef def : metricList) {
            ms.put(def.getName().toLowerCase(Locale.ROOT), def.getName());
            if (def.getDisplayName() != null) {
                ms.put(def.getDisplayName().toLowerCase(Locale.ROOT), def.getName());
            }
            def.getSynonyms().forEach(s -> ms.put(s.toLowerCase(Locale.ROOT), def.getName()));
        }
        this.metricSynonymIndex = Map.copyOf(ms);
    }

    public TableDef table(String name) {
        return name == null ? null : tables.get(name.toLowerCase(Locale.ROOT));
    }

    public MetricDef metric(String name) {
        return name == null ? null : metrics.get(name.toLowerCase(Locale.ROOT));
    }

    public ReportTemplate template(String id) {
        return id == null ? null : templates.get(id.toLowerCase(Locale.ROOT));
    }

    public JoinDef joinById(String id) {
        return id == null ? null : joins.get(id.toLowerCase(Locale.ROOT));
    }

    /** 按归一化后的 on 条件查已批准的 join，SqlGuard 的核心校验入口 */
    public JoinDef approvedJoin(String onClause) {
        return joinsByNormalizedOn.get(JoinDef.normalize(onClause));
    }

    /** 按用户说法解析表名（精确 → 同义词），解析不到返回 null */
    public String resolveTable(String term) {
        return term == null ? null : tableSynonymIndex.get(term.toLowerCase(Locale.ROOT));
    }

    /** 按用户说法解析指标名 */
    public MetricDef resolveMetric(String term) {
        if (term == null) {
            return null;
        }
        String name = metricSynonymIndex.get(term.toLowerCase(Locale.ROOT));
        return name == null ? null : metric(name);
    }

    /** 查找禁止规则，命中则返回（供生成结构化错误提示） */
    public ForbiddenJoin forbiddenJoin(String leftTable, String rightTable) {
        if (leftTable == null || rightTable == null) {
            return null;
        }
        String l = leftTable.toLowerCase(Locale.ROOT);
        String r = rightTable.toLowerCase(Locale.ROOT);
        for (ForbiddenJoin f : forbiddenJoins) {
            String fl = f.getLeft().toLowerCase(Locale.ROOT);
            String fr = f.getRight().toLowerCase(Locale.ROOT);
            if ((fl.equals(l) && fr.equals(r)) || (fl.equals(r) && fr.equals(l))) {
                return f;
            }
        }
        return null;
    }

    /** 全部物理表名（小写），SqlGuard 的表白名单 */
    public Collection<String> allTableNames() {
        return tables.keySet();
    }

    /** 某张表的全部列名（小写），SqlGuard 的列白名单 */
    public Collection<String> columnNames(String table) {
        TableDef def = table(table);
        if (def == null) {
            return List.of();
        }
        return new ArrayList<>(def.getColumnIndex().keySet());
    }

    public int size() {
        return tables.size() + metrics.size() + joins.size() + templates.size();
    }
}
