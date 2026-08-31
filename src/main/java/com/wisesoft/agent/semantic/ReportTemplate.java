package com.wisesoft.agent.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 报表模板（对应 semantic-model/templates/*.yml）
 * <p>
 * 模板走的是「参数化预置 SQL」，准确率接近 100%，因为模型只负责抽参数、
 * 不碰 SQL 本身。高频问题全部收敛到模板，NL2SQL 只兜长尾。
 *
 * @author yuanke
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class ReportTemplate {

    private String id;

    private String name;

    private String description;

    /** 示例问法，供意图分类与 Schema Linking 召回 */
    private List<String> triggers = List.of();

    private List<TemplateParam> params = List.of();

    /** 预置 SQL，用 :paramName 命名参数占位 */
    private String sql;

    private ChartHint chart;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class ChartHint {
        /** line / bar / pie / table */
        private String type;
        /**
         * 显式声明属性名：JavaBeans 规范对 {@code getXField()} 的反推结果是 "XField"
         * （前两个字母都大写时不做首字母小写化），与 YAML 里的 xField 对不上。
         */
        @JsonProperty("xField")
        private String xField;
        @JsonProperty("yFields")
        private List<String> yFields = List.of();
    }

    public TemplateParam param(String name) {
        return params.stream()
                .filter(p -> p.getName().equals(name))
                .findFirst()
                .orElse(null);
    }
}
