package com.wisesoft.agent.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表定义（对应 semantic-model/tables/*.yml）
 * <p>
 * 这里承载的是 DDL 里没有、也推不出来的东西：业务同义词、枚举值中文映射、
 * 口径注意事项。Schema Linking 靠 synonyms 召回，SQL 生成靠 notes 避坑。
 *
 * @author yuanke
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class TableDef {

    /** 物理表名 */
    private String table;

    /** 业务显示名 */
    private String displayName;

    /** 表的业务含义 */
    private String description;

    /** 粒度说明：一行代表什么 */
    private String grain;

    /** 业务同义词，Schema Linking 召回用 */
    private List<String> synonyms = List.of();

    /** 行数量级提示，供模型判断是否需要加 LIMIT */
    private Long rowCountHint;

    /** 表级口径说明 */
    private String notes;

    private List<ColumnDef> columns = List.of();

    /** 列名 → 列定义（大小写不敏感），由 SemanticModel 构建后填充 */
    private transient Map<String, ColumnDef> columnIndex = new LinkedHashMap<>();

    public ColumnDef column(String name) {
        return name == null ? null : columnIndex.get(name.toLowerCase());
    }

    /**
     * 该表的主时间列。时间范围过滤默认落在这一列上，避免模型在
     * order_date / created_at / register_date 之间乱选。
     */
    public ColumnDef primaryTimeColumn() {
        return columns.stream()
                .filter(ColumnDef::isPrimaryTimeColumn)
                .findFirst()
                .orElse(null);
    }
}
