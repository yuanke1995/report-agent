package com.wisesoft.agent.tools;

import com.wisesoft.agent.agent.AgentRunContext;
import com.wisesoft.agent.agent.AgentStep;
import com.wisesoft.agent.semantic.ReportTemplate;
import com.wisesoft.agent.semantic.SemanticModel;
import com.wisesoft.agent.semantic.TableDef;
import com.wisesoft.agent.service.ConfigService;
import com.wisesoft.agent.service.SemanticPromptBuilder;
import com.wisesoft.agent.sql.QueryResult;
import com.wisesoft.agent.sql.SqlExecutor;
import com.wisesoft.agent.sql.SqlGuard;
import com.wisesoft.agent.sql.SqlValidationException;
import com.wisesoft.agent.sql.TemplateParamValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具集。
 * <p>
 * <b>工具描述的质量决定 tool calling 的成败。</b>模型只能看到 description，
 * 看不到方法体。所以每个 description 都必须说清三件事：这个工具干什么、
 * 什么时候该用它、什么时候不该用。参数描述同理——写"日期"没用，
 * 得写"yyyy-MM-dd 格式，如 2026-08-01"。
 * <p>
 * 另一条原则：工具的返回值是给<b>模型</b>看的文本，不是给前端的数据。
 * 结构化产物（结果集、SQL、轨迹）写进 {@link AgentRunContext}，
 * 由外层取走推 SSE。混在一起会导致要么模型上下文被结果集撑爆，
 * 要么前端拿不到渲染图表所需的原始数据。
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportTools {

    private final SemanticModel model;
    private final SemanticPromptBuilder promptBuilder;
    private final SqlExecutor sqlExecutor;
    private final TemplateParamValidator paramValidator;
    private final SqlGuard sqlGuard;
    private final ConfigService configService;

    // ------------------------------------------------------------
    // 探查类工具：先了解有什么，再决定怎么查
    // ------------------------------------------------------------

    @Tool(name = "list_metrics",
            description = """
                    列出所有可用的业务指标及其**口径定义**，包括计算公式、必须带上的过滤条件、
                    以及已知的口径分歧。
                    什么时候用：用户的问题涉及任何业务指标（销售额、订单量、客单价、毛利率等）时，
                    在写 SQL 之前先调用这个工具确认口径。
                    为什么必须用：指标的算法你可能猜得对，但口径边界猜不对——
                    比如"销售额"要不要排除已取消订单、含不含退款，这些只有这里写了才算数。
                    不要跳过这一步直接凭常识写聚合表达式。
                    """)
    public String listMetrics(ToolContext toolContext) {
        AgentRunContext ctx = AgentRunContext.from(toolContext);
        AgentStep step = ctx.beginStep("list_metrics", "查询指标口径", null);
        String text = promptBuilder.renderMetrics();
        ctx.endStep(step.succeed("返回 " + model.getMetrics().size() + " 个指标定义"));
        return text;
    }

    @Tool(name = "get_table_schema",
            description = """
                    获取指定数据表的结构与业务说明，包括每一列的业务含义、单位、枚举值的中文映射、
                    以及口径注意事项；同时返回这些表之间**被批准的连接路径**。
                    什么时候用：在编写任何 SQL 之前必须先调用，确认列名和枚举值的真实取值。
                    为什么必须用：数据库里存的是英文枚举（如 order_status='paid'、
                    customer_level='gold'），而用户用中文提问（"已支付"、"金卡客户"），
                    这份映射是唯一的翻译依据，凭猜必错。连接路径同理——
                    不在批准列表里的连接会被安全校验直接拒绝。
                    """)
    public String getTableSchema(
            @ToolParam(required = true,
                    description = "表名，多个用英文逗号分隔。可用的表见系统提示中的表清单，"
                            + "例如：fact_order,dim_region")
            String tables,
            ToolContext toolContext) {
        AgentRunContext ctx = AgentRunContext.from(toolContext);
        AgentStep step = ctx.beginStep("get_table_schema", "读取表结构", tables);

        List<String> names = Arrays.stream(tables.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        List<String> unknown = names.stream().filter(n -> model.table(n) == null).toList();
        if (!unknown.isEmpty()) {
            String msg = "以下表不存在：" + unknown + "。可用的表：" + model.allTableNames();
            ctx.endStep(step.fail(msg));
            return msg;
        }

        List<String> resolved = names.stream().map(n -> model.table(n).getTable()).toList();
        String text = promptBuilder.renderTables(resolved)
                + "\n## 可用连接\n" + promptBuilder.renderJoins(resolved);
        ctx.endStep(step.succeed("返回 " + resolved.size() + " 张表的结构"));
        return text;
    }

    // ------------------------------------------------------------
    // 执行类工具
    // ------------------------------------------------------------

    @Tool(name = "run_report_template",
            description = """
                    执行一个预置的报表模板。模板的 SQL 是人工编写并验证过的，口径保证正确，
                    你只需要抽取参数。
                    什么时候用：**优先使用**。用户的问题只要能匹配上系统提示里列出的某个模板，
                    就走这条路，不要自己写 SQL。模板路径的准确率远高于自己生成 SQL。
                    什么时候不用：问题的维度或指标组合明显超出所有模板的能力范围时，
                    改用 execute_sql。
                    时间参数处理：用户说"上个月""最近半年"这类相对时间，你需要结合系统提示里
                    给出的当前日期换算成具体的年月日再传入，不要原样传中文。
                    """)
    public String runReportTemplate(
            @ToolParam(required = true, description = "模板 ID，见系统提示中的模板清单，例如 sales_monthly_summary")
            String templateId,
            @ToolParam(required = true,
                    description = "参数键值对的 JSON 对象，值一律用字符串。"
                            + "例如：{\"startMonth\":\"2026-03\",\"endMonth\":\"2026-08\"}。"
                            + "没有参数时传 {}")
            String paramsJson,
            ToolContext toolContext) {
        AgentRunContext ctx = AgentRunContext.from(toolContext);
        AgentStep step = ctx.beginStep("run_report_template", "执行报表模板", templateId + " " + paramsJson);

        ReportTemplate template = model.template(templateId);
        if (template == null) {
            String msg = "模板 " + templateId + " 不存在。可用模板："
                    + model.getTemplates().keySet()
                    + "。如果都不匹配，请改用 execute_sql 自行编写查询。";
            ctx.endStep(step.fail(msg));
            return msg;
        }

        try {
            Map<String, String> raw = parseParams(paramsJson);
            Map<String, Object> bound = paramValidator.validate(template, raw);
            QueryResult result = sqlExecutor.executeTemplate(template.getSql(), bound);

            ctx.setTemplateId(templateId);
            ctx.setRoute("template");
            ctx.recordResult(result);
            ctx.endStep(step.succeed(result.rowCount() + " 行 / " + result.getElapsedMs() + "ms"));
            return "模板 " + templateId + "（" + template.getName() + "）执行成功。\n\n"
                    + result.toModelText();
        } catch (SqlValidationException e) {
            ctx.endStep(step.fail(e.getMessage()));
            return e.toModelText();
        } catch (IllegalArgumentException e) {
            ctx.endStep(step.fail(e.getMessage()));
            return "参数解析失败：" + e.getMessage() + "\n请以合法 JSON 对象的形式提供参数。";
        }
    }

    @Tool(name = "execute_sql",
            description = """
                    在业务库上执行一条只读 SQL 查询并返回结果。
                    什么时候用：问题无法匹配任何报表模板时的兜底路径。
                    使用前提（不满足会被拒绝）：
                    1. 必须先调用 get_table_schema 确认列名和枚举值，不要凭记忆写列名；
                    2. 涉及指标时必须先调用 list_metrics 确认口径，并把口径边界写进 WHERE；
                    3. 只能写 SELECT，且只能使用被批准的连接路径；
                    4. 方言是 MySQL 8.0。
                    如果被拒绝，返回的错误信息里会附带具体的修复建议，请据此修改后重试，
                    不要重复提交同一条 SQL。
                    """)
    public String executeSql(
            @ToolParam(required = true, description = "完整的 SELECT 语句，MySQL 8.0 方言，不要加分号")
            String sql,
            ToolContext toolContext) {
        AgentRunContext ctx = AgentRunContext.from(toolContext);
        AgentStep step = ctx.beginStep("execute_sql", "执行查询", sql);

        // 修复次数上限：SQL 反复出错就停止重试，避免无限烧 token
        if (ctx.getRepairCount() >= maxSqlRepairs(ctx)) {
            String msg = "SQL 已重试 " + ctx.getRepairCount() + " 次仍未通过校验，本轮不再继续尝试。"
                    + "请换个思路：重新检查 get_table_schema 返回的结构，或改用 run_report_template 匹配现有模板。";
            ctx.endStep(step.fail(msg));
            return msg;
        }

        try {
            // 第一道防线：SqlGuard 的 AST 白名单校验（表/列/join/危险函数/LIMIT 注入）
            SqlGuard.GuardResult guarded = sqlGuard.validate(sql);
            // 第二道防线：EXPLAIN 干跑
            sqlExecutor.explainDryRun(guarded.guardedSql());
            // 第三道防线：只读账号 + 行数/超时上限（在 SqlExecutor 里无条件生效）
            QueryResult result = sqlExecutor.executeGenerated(guarded.guardedSql());

            ctx.setRoute("nl2sql");
            ctx.recordResult(result);
            String note = guarded.limitInjected() ? "（已自动注入行数上限）" : "";
            ctx.endStep(step.succeed(result.rowCount() + " 行 / " + result.getElapsedMs() + "ms " + note));
            return result.toModelText();
        } catch (SqlValidationException e) {
            ctx.incrementRepair();
            ctx.endStep(step.fail(e.getMessage()));
            return e.toModelText();
        }
    }

    private int maxSqlRepairs(AgentRunContext ctx) {
        return configService.getInt("agent.maxSqlRepairs", 2);
    }

    // ------------------------------------------------------------
    // 澄清：宁可反问，不要猜
    // ------------------------------------------------------------

    @Tool(name = "ask_clarification",
            returnDirect = true,
            description = """
                    当用户的问题存在真实歧义、无法确定该用哪个口径时，向用户反问。
                    什么时候用：比如用户问"上个月销售情况"，但"销售额"存在含不含退款的口径分歧；
                    或者用户说"华东"，但不确定是指订单收货地区还是客户注册地区。
                    什么时候不用：只是你自己不确定该用哪张表——那应该去调 get_table_schema，
                    而不是让用户替你做技术决策。反问只针对**业务口径**的歧义。
                    注意：调用这个工具会直接结束本轮对话并把问题抛给用户，
                    所以确实存在歧义时才用，不要滥用。
                    """)
    public String askClarification(
            @ToolParam(required = true, description = "要问用户的问题，一句话说清歧义在哪")
            String question,
            @ToolParam(required = true,
                    description = "供用户选择的选项，用英文分号分隔，2~4 个。"
                            + "例如：不含退款（标准口径）;含退款的总流水;扣减退款后的净额")
            String options,
            ToolContext toolContext) {
        AgentRunContext ctx = AgentRunContext.from(toolContext);
        AgentStep step = ctx.beginStep("ask_clarification", "请用户澄清口径", question);

        List<String> opts = Arrays.stream(options.split(";"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        payload.put("options", opts);

        ctx.setRoute("clarify");
        ctx.setClarification(question);
        ctx.endStep(step.succeed(question));

        StringBuilder sb = new StringBuilder(question).append("\n\n");
        for (int i = 0; i < opts.size(); i++) {
            sb.append(i + 1).append(". ").append(opts.get(i)).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------

    /**
     * 解析模型传来的参数 JSON。模型偶尔会返回 null、空串或者带 Markdown 代码块包裹的
     * JSON，这里统一容错——为这种小格式问题浪费一整轮重试不划算。
     */
    private Map<String, String> parseParams(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        String s = json.trim();
        if (s.startsWith("```")) {
            s = s.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        if (s.isEmpty() || "null".equals(s)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = com.alibaba.fastjson2.JSON.parseObject(s);
            Map<String, String> out = new LinkedHashMap<>();
            if (parsed != null) {
                parsed.forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("不是合法的 JSON 对象：" + s);
        }
    }

    /** 供系统提示渲染表清单用 */
    public List<TableDef> allTables() {
        return List.copyOf(model.getTables().values());
    }
}
