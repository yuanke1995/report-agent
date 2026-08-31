package com.wisesoft.agent.sql;

import com.wisesoft.agent.semantic.ReportTemplate;
import com.wisesoft.agent.semantic.TemplateParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 模板参数校验与转换。
 * <p>
 * 模型抽出来的参数值不可信 —— 它会把"上个月"抽成 "2026-7" 而不是 "2026-07"，
 * 会把 topN 抽成 "十"，也会在枚举参数里填一个不存在的类目。这些错误如果直接
 * 绑到 SQL 上，轻则报错重则静默查出空结果。
 * <p>
 * 所以每个参数都按声明的 type 强校验，不合格就抛结构化异常回灌给模型重抽。
 * 校验通过后转成正确的 Java 类型再交给 JDBC 绑定。
 *
 * @author yuanke
 */
@Component
public class TemplateParamValidator {

    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern MONTH_PATTERN = Pattern.compile("\\d{4}-\\d{2}");
    /** 自由文本参数的字符白名单：中英文数字与常见标点，拒绝引号、分号、注释符 */
    private static final Pattern SAFE_TEXT = Pattern.compile("[\\u4e00-\\u9fa5A-Za-z0-9_\\-. ]{0,64}");

    /**
     * 校验并转换。返回可直接交给 NamedParameterJdbcTemplate 的参数表。
     *
     * @param raw 模型抽出的原始参数（全部是字符串）
     * @throws SqlValidationException 校验失败，附带逐条修复建议
     */
    public Map<String, Object> validate(ReportTemplate template, Map<String, String> raw) {
        Map<String, Object> bound = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (TemplateParam p : template.getParams()) {
            String value = raw == null ? null : raw.get(p.getName());
            if (value != null) {
                value = value.trim();
                if (value.isEmpty()) {
                    value = null;
                }
            }
            if (value == null) {
                value = p.getDefaultValue();
            }
            if (value == null || value.isBlank()) {
                if (p.isRequired()) {
                    errors.add("缺少必填参数 " + p.getName()
                            + "（" + nz(p.getDisplayName()) + "）：" + nz(p.getDescription()));
                } else {
                    // 可选参数缺省绑 null，模板里用 (:x IS NULL OR col = :x) 的写法接住
                    bound.put(p.getName(), null);
                }
                continue;
            }
            try {
                bound.put(p.getName(), convert(p, value));
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
            }
        }

        // 传了模板里没有的参数：说明模型抽错了模板或者臆造了参数，属于真错误不能忽略
        if (raw != null) {
            for (String key : raw.keySet()) {
                if (template.param(key) == null) {
                    errors.add("参数 " + key + " 不属于模板 " + template.getId()
                            + "，可用参数：" + template.getParams().stream()
                            .map(TemplateParam::getName).toList());
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new SqlValidationException(SqlValidationException.Stage.GUARD,
                    "模板 " + template.getId() + " 的参数校验未通过", errors);
        }
        return bound;
    }

    private Object convert(TemplateParam p, String value) {
        return switch (p.getType()) {
            case DATE -> {
                if (!DATE_PATTERN.matcher(value).matches()) {
                    throw new IllegalArgumentException(
                            "参数 " + p.getName() + " 必须是 yyyy-MM-dd 格式，当前值：" + value);
                }
                try {
                    yield LocalDate.parse(value);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException(
                            "参数 " + p.getName() + " 不是合法日期：" + value);
                }
            }
            case MONTH -> {
                if (!MONTH_PATTERN.matcher(value).matches()) {
                    throw new IllegalArgumentException(
                            "参数 " + p.getName() + " 必须是 yyyy-MM 格式（月份补零，如 2026-07），当前值：" + value);
                }
                try {
                    YearMonth.parse(value);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException(
                            "参数 " + p.getName() + " 不是合法月份：" + value);
                }
                // 月份保持字符串：模板里用 DATE_FORMAT(...,'%Y-%m') 做字符串比较
                yield value;
            }
            case INT -> {
                int i;
                try {
                    i = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "参数 " + p.getName() + " 必须是整数，当前值：" + value);
                }
                checkRange(p, i);
                yield i;
            }
            case DECIMAL -> {
                double d;
                try {
                    d = Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "参数 " + p.getName() + " 必须是数字，当前值：" + value);
                }
                checkRange(p, d);
                yield d;
            }
            case ENUM -> {
                if (!p.getOptions().contains(value)) {
                    throw new IllegalArgumentException(
                            "参数 " + p.getName() + " 的值 \"" + value + "\" 不在可选范围内，"
                                    + "可选值：" + p.getOptions());
                }
                yield value;
            }
            case STRING -> {
                if (!SAFE_TEXT.matcher(value).matches()) {
                    throw new IllegalArgumentException(
                            "参数 " + p.getName() + " 含有不允许的字符（仅允许中英文、数字、下划线、连字符、点、空格），"
                                    + "当前值：" + value);
                }
                yield value;
            }
        };
    }

    private void checkRange(TemplateParam p, double v) {
        if (p.getMin() != null && v < p.getMin()) {
            throw new IllegalArgumentException(
                    "参数 " + p.getName() + " 不能小于 " + fmt(p.getMin()) + "，当前值：" + fmt(v));
        }
        if (p.getMax() != null && v > p.getMax()) {
            throw new IllegalArgumentException(
                    "参数 " + p.getName() + " 不能大于 " + fmt(p.getMax()) + "，当前值：" + fmt(v));
        }
    }

    private String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }
}
