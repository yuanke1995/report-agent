package com.wisesoft.agent.service;

import com.wisesoft.agent.semantic.ColumnDef;
import com.wisesoft.agent.semantic.ForbiddenJoin;
import com.wisesoft.agent.semantic.JoinDef;
import com.wisesoft.agent.semantic.MetricDef;
import com.wisesoft.agent.semantic.ReportTemplate;
import com.wisesoft.agent.semantic.SemanticModel;
import com.wisesoft.agent.semantic.TableDef;
import com.wisesoft.agent.semantic.TemplateParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 把语义层渲染成模型能读的文本。
 * <p>
 * 这一层的取舍全是上下文预算：把六张表的完整描述一股脑塞进去要几千 token，
 * 而模型真正需要的往往只有两三张表。所以渲染方法都接受表名范围参数，
 * 由调用方（阶段 4 的 Schema Linking）决定注入哪些。
 * <p>
 * 输出格式刻意用 Markdown 而非 JSON：同样的信息量，Markdown 的 token 数
 * 明显更少，而模型对表格结构的理解并不比 JSON 差。
 *
 * @author yuanke
 */
@Component
@RequiredArgsConstructor
public class SemanticPromptBuilder {

    private final SemanticModel model;

    /**
     * 渲染指定表的结构。
     * 每列给出：列名、类型、业务名、角色、枚举值中文映射、口径注意事项。
     * 其中枚举映射和 notes 是 DDL 里没有的部分，也正是准确率的来源。
     */
    public String renderTables(Collection<String> tableNames) {
        StringBuilder sb = new StringBuilder();
        for (String name : tableNames) {
            TableDef t = model.table(name);
            if (t == null) {
                continue;
            }
            sb.append("### 表 ").append(t.getTable());
            if (t.getDisplayName() != null) {
                sb.append("（").append(t.getDisplayName()).append("）");
            }
            sb.append('\n');
            if (t.getDescription() != null) {
                sb.append(t.getDescription()).append('\n');
            }
            if (t.getGrain() != null) {
                sb.append("粒度：").append(t.getGrain()).append('\n');
            }
            if (t.getRowCountHint() != null) {
                sb.append("数据量：约 ").append(t.getRowCountHint()).append(" 行\n");
            }
            sb.append('\n');
            sb.append("| 列名 | 类型 | 业务含义 | 说明 |\n|---|---|---|---|\n");
            for (ColumnDef c : t.getColumns()) {
                sb.append("| ").append(c.getName())
                        .append(" | ").append(nz(c.getType()))
                        .append(" | ").append(nz(c.getDisplayName()))
                        .append(" | ").append(columnNote(c))
                        .append(" |\n");
            }
            if (t.getNotes() != null) {
                sb.append("\n注意：").append(oneLine(t.getNotes())).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 单列的说明列：描述 + 单位 + 枚举映射 + 口径提醒，压成一行 */
    private String columnNote(ColumnDef c) {
        StringBuilder sb = new StringBuilder();
        if (c.getDescription() != null) {
            sb.append(oneLine(c.getDescription()));
        }
        if (c.getUnit() != null) {
            sb.append("（单位：").append(c.getUnit()).append("）");
        }
        if (c.hasEnums()) {
            sb.append(" 取值：");
            sb.append(c.getEnums().entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + ", " + b).orElse(""));
        }
        if (c.getNotes() != null) {
            sb.append(" ⚠ ").append(oneLine(c.getNotes()));
        }
        return sb.toString().replace("|", "\\|");
    }

    /**
     * 渲染指标口径。这是整个 prompt 里最值钱的部分——
     * BIRD 的消融实验显示，去掉这类业务知识注入会掉 14 分。
     */
    public String renderMetrics() {
        StringBuilder sb = new StringBuilder();
        for (MetricDef m : model.getMetrics().values()) {
            sb.append("- **").append(m.getDisplayName()).append("**（").append(m.getName()).append("）");
            if (!m.getSynonyms().isEmpty()) {
                sb.append(" 别名：").append(String.join("、", m.getSynonyms()));
            }
            sb.append('\n');
            sb.append("  - 算法：`").append(m.getExpression()).append("`\n");
            if (!m.getRequiredFilters().isEmpty()) {
                sb.append("  - **口径边界（必须带上）**：");
                sb.append(String.join(" AND ", m.getRequiredFilters())).append('\n');
            }
            if (!m.getRequiredJoins().isEmpty()) {
                sb.append("  - 需要连接：").append(String.join(", ", m.getRequiredJoins())).append('\n');
            }
            if (!m.getCaveats().isEmpty()) {
                for (String c : m.getCaveats()) {
                    sb.append("  - ⚠ ").append(oneLine(c)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** 渲染指定表之间已批准的连接路径，以及明确禁止的连接 */
    public String renderJoins(Collection<String> tableNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("只允许使用下列连接，未列出的连接一律会被拒绝：\n");
        for (JoinDef j : model.getJoins().values()) {
            if (!tableNames.contains(j.getLeft()) || !tableNames.contains(j.getRight())) {
                continue;
            }
            sb.append("- `").append(j.getOn()).append("`")
                    .append("（").append(j.getType()).append("，").append(nz(j.getCardinality())).append("）");
            if (j.getDescription() != null) {
                sb.append(" —— ").append(oneLine(j.getDescription()));
            }
            sb.append('\n');
        }
        List<ForbiddenJoin> forbidden = model.getForbiddenJoins().stream()
                .filter(f -> tableNames.contains(f.getLeft()) && tableNames.contains(f.getRight()))
                .toList();
        if (!forbidden.isEmpty()) {
            sb.append("\n明确禁止：\n");
            for (ForbiddenJoin f : forbidden) {
                sb.append("- ").append(f.getLeft()).append(" 与 ").append(f.getRight())
                        .append(" 不能直连：").append(oneLine(f.getReason())).append('\n');
            }
        }
        return sb.toString();
    }

    /** 渲染报表模板目录，供模型做意图匹配 */
    public String renderTemplateCatalog() {
        StringBuilder sb = new StringBuilder();
        for (ReportTemplate t : model.getTemplates().values()) {
            sb.append("- **").append(t.getId()).append("** ").append(nz(t.getName())).append('\n');
            sb.append("  - 用途：").append(oneLine(nz(t.getDescription()))).append('\n');
            sb.append("  - 参数：");
            if (t.getParams().isEmpty()) {
                sb.append("无");
            } else {
                sb.append(t.getParams().stream()
                        .map(this::paramBrief)
                        .reduce((a, b) -> a + "; " + b).orElse(""));
            }
            sb.append('\n');
            if (!t.getTriggers().isEmpty()) {
                sb.append("  - 典型问法：").append(String.join(" / ", t.getTriggers())).append('\n');
            }
        }
        return sb.toString();
    }

    private String paramBrief(TemplateParam p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.getName()).append('(').append(p.getType().name().toLowerCase());
        sb.append(p.isRequired() ? ",必填" : ",可选");
        if (p.getType() == TemplateParam.ParamType.ENUM) {
            sb.append(",取值=").append(p.getOptions());
        }
        if (p.getDefaultValue() != null) {
            sb.append(",默认=").append(p.getDefaultValue());
        }
        sb.append(')');
        if (p.getDescription() != null) {
            sb.append(' ').append(oneLine(p.getDescription()));
        }
        return sb.toString();
    }

    /** 表清单概览，让模型知道有哪些表可选（不含列，很省 token） */
    public String renderTableCatalog() {
        StringBuilder sb = new StringBuilder();
        for (TableDef t : model.getTables().values()) {
            sb.append("- ").append(t.getTable())
                    .append("（").append(nz(t.getDisplayName())).append("）：")
                    .append(oneLine(nz(t.getDescription())));
            if (!t.getSynonyms().isEmpty()) {
                sb.append(" 别名：").append(String.join("、", t.getSynonyms()));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 枚举值中文→英文的反向映射，供参数抽取时对照 */
    public Map<String, Map<String, String>> enumReverseIndex() {
        return model.getTables().values().stream()
                .flatMap(t -> t.getColumns().stream()
                        .filter(ColumnDef::hasEnums)
                        .map(c -> Map.entry(t.getTable() + "." + c.getName(), c.getEnums())))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    private String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
