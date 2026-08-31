package com.wisesoft.agent.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Golden 示例（对应 semantic-model/golden/examples.yml）
 * <p>
 * NL2SQL 的 few-shot 语料：人工验证过正确性的「问题 → SQL」对。
 * 模型模仿的是写法，不是凭空生成。
 *
 * @author yuanke
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class GoldenExample {

    private String id;

    /** 典型问法，Schema Linking 召回用 */
    private String question;

    /** 关键词（分词后的），召回打分用 */
    private List<String> keywords = List.of();

    /** 该示例涉及的物理表 */
    private List<String> tables = List.of();

    /** 人工验证过的正确 SQL */
    private String sql;

    /** 为什么这么写：口径要点，注入 prompt 时一起给模型看 */
    private String notes;
}
