package com.wisesoft.agent.sql;

import com.wisesoft.agent.semantic.ReportTemplate;
import com.wisesoft.agent.semantic.SemanticModel;
import com.wisesoft.agent.semantic.SemanticModelLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模板参数校验测试。
 * <p>
 * 覆盖的是模型实际会犯的错：月份不补零、数字写成中文、枚举值臆造、
 * 参数名张冠李戴。这些如果不拦住，轻则 SQL 报错重则静默查出空结果。
 *
 * @author yuanke
 */
class TemplateParamValidatorTest {

    private static final SemanticModel MODEL = new SemanticModelLoader().load();
    private final TemplateParamValidator validator = new TemplateParamValidator();

    private ReportTemplate monthly() {
        return MODEL.template("sales_monthly_summary");
    }

    private ReportTemplate topN() {
        return MODEL.template("product_top_n");
    }

    @Test
    @DisplayName("合法参数正常转换")
    void acceptsValid() {
        Map<String, Object> bound = validator.validate(monthly(),
                Map.of("startMonth", "2026-03", "endMonth", "2026-08"));
        assertEquals("2026-03", bound.get("startMonth"));
        assertEquals("2026-08", bound.get("endMonth"));
    }

    @Test
    @DisplayName("日期参数转成 LocalDate，模板里能直接比较")
    void convertsDateType() {
        Map<String, Object> bound = validator.validate(topN(),
                Map.of("startDate", "2026-08-01", "endDate", "2026-08-31"));
        assertEquals(LocalDate.of(2026, 8, 1), bound.get("startDate"));
        assertEquals(LocalDate.of(2026, 8, 31), bound.get("endDate"));
    }

    @Test
    @DisplayName("月份不补零被拒绝——模型高频错误")
    void rejectsUnpaddedMonth() {
        SqlValidationException e = assertThrows(SqlValidationException.class,
                () -> validator.validate(monthly(), Map.of("startMonth", "2026-7", "endMonth", "2026-08")));
        assertEquals(SqlValidationException.Stage.GUARD, e.getStage());
        assertTrue(e.getHints().stream().anyMatch(h -> h.contains("yyyy-MM")),
                "修复建议里要给出正确格式，模型才知道怎么改");
    }

    @Test
    @DisplayName("日期格式错误被拒绝")
    void rejectsBadDate() {
        assertThrows(SqlValidationException.class,
                () -> validator.validate(topN(), Map.of("startDate", "2026/08/01", "endDate", "2026-08-31")));
    }

    @Test
    @DisplayName("缺少必填参数被拒绝，且提示里带上参数说明")
    void rejectsMissingRequired() {
        SqlValidationException e = assertThrows(SqlValidationException.class,
                () -> validator.validate(monthly(), Map.of("startMonth", "2026-03")));
        assertTrue(e.getHints().stream().anyMatch(h -> h.contains("endMonth")));
    }

    @Test
    @DisplayName("可选参数缺省时绑 null，模板用 IS NULL 分支接住")
    void optionalDefaultsToNull() {
        Map<String, Object> bound = validator.validate(topN(),
                Map.of("startDate", "2026-08-01", "endDate", "2026-08-31"));
        assertNull(bound.get("categoryL1"), "未提供的可选枚举参数应为 null");
        assertEquals(10, bound.get("topN"), "topN 应取 YAML 里声明的默认值");
    }

    @Test
    @DisplayName("枚举值不在选项内被拒绝，并回列出可选值")
    void rejectsUnknownEnum() {
        Map<String, String> params = new HashMap<>();
        params.put("startDate", "2026-08-01");
        params.put("endDate", "2026-08-31");
        params.put("categoryL1", "图书音像");
        SqlValidationException e = assertThrows(SqlValidationException.class,
                () -> validator.validate(topN(), params));
        assertTrue(e.getHints().stream().anyMatch(h -> h.contains("手机数码")),
                "要把可选值列给模型，否则它只能继续猜");
    }

    @Test
    @DisplayName("整数越界被拒绝")
    void rejectsOutOfRange() {
        Map<String, String> params = new HashMap<>();
        params.put("startDate", "2026-08-01");
        params.put("endDate", "2026-08-31");
        params.put("topN", "5000");
        SqlValidationException e = assertThrows(SqlValidationException.class,
                () -> validator.validate(topN(), params));
        assertTrue(e.getHints().stream().anyMatch(h -> h.contains("100")));
    }

    @Test
    @DisplayName("非数字的 topN 被拒绝")
    void rejectsNonNumeric() {
        Map<String, String> params = new HashMap<>();
        params.put("startDate", "2026-08-01");
        params.put("endDate", "2026-08-31");
        params.put("topN", "十");
        assertThrows(SqlValidationException.class, () -> validator.validate(topN(), params));
    }

    @Test
    @DisplayName("臆造的参数名被拒绝——说明模型抽错了模板")
    void rejectsUnknownParam() {
        Map<String, String> params = new HashMap<>();
        params.put("startMonth", "2026-03");
        params.put("endMonth", "2026-08");
        params.put("region", "华东");
        SqlValidationException e = assertThrows(SqlValidationException.class,
                () -> validator.validate(monthly(), params));
        assertTrue(e.getHints().stream().anyMatch(h -> h.contains("region")));
    }

    @Test
    @DisplayName("结构化错误能渲染成给模型的修复提示")
    void rendersModelText() {
        SqlValidationException e = assertThrows(SqlValidationException.class,
                () -> validator.validate(monthly(), Map.of("startMonth", "2026-7", "endMonth", "2026-08")));
        String text = e.toModelText();
        assertTrue(text.contains("安全校验"));
        assertTrue(text.contains("修复建议"));
        assertTrue(text.contains("不要重复提交同一条 SQL"));
    }
}
