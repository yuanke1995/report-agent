package com.wisesoft.agent.eval;

import com.wisesoft.agent.service.SchemaLinkingService;
import com.wisesoft.agent.service.SemanticPromptBuilder;
import com.wisesoft.agent.sql.QueryResult;
import com.wisesoft.agent.sql.SqlExecutor;
import com.wisesoft.agent.sql.SqlGuard;
import com.wisesoft.agent.sql.SqlValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * NL2SQL 评测器。
 * <p>
 * 用法：
 * <ul>
 *   <li>{@code java -jar report-agent.jar --eval} —— 完整评测：每题让模型生成 SQL，
 *       执行后与标准 SQL 的结果集对比（需要可用的模型 key）</li>
 *   <li>{@code java -jar report-agent.jar --eval-self} —— 自检模式：模型输出用标准 SQL
 *       代替，验证评测链路与对比逻辑本身（不需要模型）</li>
 * </ul>
 * 对比标准：结果集的行集合相等（列按位置对应，数值列比较到小数点后 2 位）。
 * 不要求 SQL 文本相同——语义等价即可，这正是执行级评测的意义。
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Nl2SqlEvaluator implements ApplicationRunner {

    private final ChatModel chatModel;
    private final SchemaLinkingService schemaLinkingService;
    private final SemanticPromptBuilder promptBuilder;
    private final SqlGuard sqlGuard;
    private final SqlExecutor sqlExecutor;

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("eval") && !args.containsOption("eval-self")) {
            return;
        }
        boolean selfTest = args.containsOption("eval-self");
        EvalSetLoader.EvalSet set = new EvalSetLoader().load();
        log.info("==============================================");
        log.info("NL2SQL 评测开始：{} 题{}", set.getCases().size(), selfTest ? "（自检模式，不调用模型）" : "");
        log.info("==============================================");

        int pass = 0;
        int guardRejected = 0;
        int execFailed = 0;
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < set.getCases().size(); i++) {
            EvalSetLoader.Case c = set.getCases().get(i);
            String result = evaluateOne(c, selfTest);
            if ("PASS".equals(result)) {
                pass++;
            } else if ("GUARD".equals(result)) {
                guardRejected++;
            } else if ("EXEC".equals(result)) {
                execFailed++;
            } else {
                failures.add((i + 1) + ". " + c.getQuestion() + " —— " + result);
            }
        }

        log.info("==============================================");
        log.info("评测结果：通过 {}/{}（{}%）  Guard 拒绝 {}  执行失败 {}",
                pass, set.getCases().size(),
                set.getCases().isEmpty() ? 0 : pass * 100 / set.getCases().size(),
                guardRejected, execFailed);
        if (!failures.isEmpty()) {
            log.info("不一致明细：");
            failures.forEach(f -> log.info("  {}", f));
        }
        log.info("==============================================");
        // 评测是诊断工具，跑完正常退出
        System.exit(0);
    }

    private String evaluateOne(EvalSetLoader.Case c, boolean selfTest) {
        String generated;
        if (selfTest) {
            generated = c.getExpectedSql();
        } else {
            generated = generateSql(c.getQuestion());
            if (generated == null || generated.isBlank()) {
                return "模型未返回 SQL";
            }
            log.info("[评测] 第题生成 SQL:\n{}", generated);
        }

        // 1. SqlGuard 校验（与生产同一条防线）
        String guarded;
        try {
            guarded = sqlGuard.validate(generated).guardedSql();
        } catch (SqlValidationException e) {
            log.warn("[评测] Guard 拒绝: {}", e.getMessage());
            return "GUARD";
        }

        // 2. 执行模型 SQL 与标准 SQL
        QueryResult got;
        QueryResult expected;
        try {
            got = sqlExecutor.executeGenerated(guarded);
            expected = sqlExecutor.executeGenerated(c.getExpectedSql());
        } catch (SqlValidationException e) {
            log.warn("[评测] 执行失败: {}", e.getMessage());
            return "EXEC";
        }

        // 3. 结果集对比（行集合等价，数值容差 0.01）
        boolean same = sameResultSet(got, expected);
        if (!same) {
            log.info("[评测] 不一致 问题={}", c.getQuestion());
            log.info("  期望 {} 行, 实际 {} 行", expected.rowCount(), got.rowCount());
            if (got.rowCount() > 0 && expected.rowCount() > 0) {
                log.info("  期望首行: {}", expected.getRows().get(0));
                log.info("  实际首行: {}", got.getRows().get(0));
            }
            return "结果不一致";
        }
        log.info("[评测] ✓ {}（{} 行）", c.getQuestion(), got.rowCount());
        return "PASS";
    }

    /** 让模型直接生成 SQL（单次调用，不带工具循环——评测的是生成质量本身） */
    private String generateSql(String question) {
        SchemaLinkingService.LinkingResult linked = schemaLinkingService.link(question);
        String system = """
                你是资深数据分析师。根据给定的表结构和口径定义，为问题生成一条 MySQL 8.0 的
                SELECT 语句。只输出 SQL 本身，不要任何解释、不要 Markdown 代码块、不要分号。
                今天是 %s。

                ## 相关表结构
                %s
                ## 相关指标口径
                %s
                ## 参考示例
                %s
                """.formatted(
                LocalDate.now(),
                promptBuilder.renderTables(linked.tables()),
                promptBuilder.renderMetrics(linked.metricNames()),
                promptBuilder.renderGoldenExamples(linked.goldenExamples()));

        ChatResponse response = chatModel.call(new Prompt(
                List.of(new SystemMessage(system), new UserMessage(question))));
        String text = response.getResult().getOutput().getText();
        if (text == null) {
            return null;
        }
        // 容错：模型偶尔会带 ```sql 包裹
        String t = text.trim();
        if (t.startsWith("```")) {
            t = t.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        return t;
    }

    /** 结果集等价：行集合相同（数值容忍 0.01 误差），列顺序忽略 */
    private boolean sameResultSet(QueryResult got, QueryResult expected) {
        if (got.getColumns().size() != expected.getColumns().size()) {
            return false;
        }
        Set<String> gotRows = new LinkedHashSet<>();
        for (var row : got.getRows()) {
            gotRows.add(fingerprint(row, got.getColumns()));
        }
        Set<String> expectedRows = new LinkedHashSet<>();
        for (var row : expected.getRows()) {
            expectedRows.add(fingerprint(row, expected.getColumns()));
        }
        return gotRows.equals(expectedRows);
    }

    /** 行指纹：列值按列名排序后拼接（列顺序不同不影响），数值四舍五入到 2 位小数 */
    private String fingerprint(java.util.Map<String, Object> row, List<String> columns) {
        List<String> cells = new ArrayList<>();
        for (String col : columns) {
            Object v = row.get(col);
            if (v instanceof Number n) {
                cells.add(col + "=" + Math.round(n.doubleValue() * 100) / 100.0);
            } else {
                cells.add(col + "=" + String.valueOf(v));
            }
        }
        cells.sort(String::compareTo);
        return String.join("|", cells);
    }
}
