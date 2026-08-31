package com.wisesoft.agent.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 模板参数定义
 * <p>
 * type 不只是描述，是执行前的强校验依据：模型抽出来的参数值必须
 * 通过 {@link com.wisesoft.agent.sql.TemplateParamValidator} 的格式与范围检查
 * 才会绑定到 SQL。参数走 JDBC 命名参数绑定，不做字符串拼接，
 * 因此模板路径天然免疫 SQL 注入。
 *
 * @author yuanke
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class TemplateParam {

    public enum ParamType {
        /** yyyy-MM-dd */
        DATE,
        /** yyyy-MM */
        MONTH,
        /** 整数 */
        INT,
        /** 小数 */
        DECIMAL,
        /** 必须是 options 之一 */
        ENUM,
        /** 自由文本（仍会做长度与字符校验） */
        STRING
    }

    private String name;

    private ParamType type = ParamType.STRING;

    private String displayName;

    private String description;

    private boolean required;

    /** 未提供时的默认值（字符串形式，按 type 解析） */
    private String defaultValue;

    /** ENUM 类型的合法取值 */
    private List<String> options = List.of();

    /** INT / DECIMAL 的取值范围 */
    private Double min;
    private Double max;
}
