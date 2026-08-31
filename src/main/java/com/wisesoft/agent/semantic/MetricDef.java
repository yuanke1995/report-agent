package com.wisesoft.agent.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 指标定义（对应 semantic-model/metrics.yml）
 * <p>
 * requiredFilters 是这个类存在的主要理由。指标的算术表达式模型多半能猜对，
 * 猜不对的是口径边界——「销售额」要不要排除已取消订单、含不含退款。
 * 把这些写死在这里，比在 prompt 里反复叮嘱可靠得多。
 *
 * @author yuanke
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class MetricDef {

    /** 指标英文标识 */
    private String name;

    /** 业务显示名 */
    private String displayName;

    /** 同义词，用户可能的各种说法 */
    private List<String> synonyms = List.of();

    private String description;

    /** 聚合表达式，列名需带表名前缀 */
    private String expression;

    /** 表达式的主表 */
    private String baseTable;

    /** 计算该指标必须先建立的 join（joins.yml 的 id） */
    private List<String> requiredJoins = List.of();

    /** 口径边界：任何使用该指标的查询都必须带上这些过滤条件 */
    private List<String> requiredFilters = List.of();

    private String unit;

    /** 展示格式，前端格式化用 */
    private String format;

    /**
     * 已知的口径分歧。歧义问题触发 ask_clarification 时，
     * 这里的内容直接作为反问的选项来源。
     */
    private List<String> caveats = List.of();
}
