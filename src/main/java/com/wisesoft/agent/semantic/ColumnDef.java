package com.wisesoft.agent.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 列定义（对应 tables/*.yml 的 columns 项）
 *
 * @author yuanke
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class ColumnDef {

    /** 列的角色，决定它在查询里能出现的位置 */
    public enum Role {
        /** 主键/外键，不参与聚合也不适合直接展示 */
        ID,
        /** 维度，可用于 GROUP BY / WHERE */
        DIMENSION,
        /** 度量，可用于聚合 */
        MEASURE,
        /** 时间列 */
        TIME
    }

    private String name;

    private String type;

    private String displayName;

    private String description;

    private Role role = Role.DIMENSION;

    /**
     * 是否为本表的主时间列。
     * <p>
     * 显式声明 JSON 属性名：Lombok 给 {@code boolean isPrimaryTimeColumn} 生成的
     * getter 是 {@code isPrimaryTimeColumn()}，Jackson 会把属性名推断成
     * "primaryTimeColumn"，与 YAML 里的写法对不上。
     */
    @JsonProperty("isPrimaryTimeColumn")
    private boolean isPrimaryTimeColumn;

    private String unit;

    private List<String> synonyms = List.of();

    /**
     * 枚举值 → 中文含义。数据库存英文枚举、用户用中文提问时，
     * 这份映射是唯一的翻译依据。
     */
    private Map<String, String> enums = new LinkedHashMap<>();

    /** 口径注意事项，注入 prompt 用 */
    private String notes;

    public boolean hasEnums() {
        return enums != null && !enums.isEmpty();
    }
}
