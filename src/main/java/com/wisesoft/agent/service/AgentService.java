package com.wisesoft.agent.service;

import com.wisesoft.agent.agent.AgentRunContext;
import com.wisesoft.agent.agent.AgentStep;
import com.wisesoft.agent.config.AgentProperties;
import com.wisesoft.agent.semantic.SemanticModel;
import com.wisesoft.agent.sql.QueryResult;
import com.wisesoft.agent.tools.ReportTools;
import com.wisesoft.agent.util.SseSender;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 手写 ReAct 循环（阶段 3 核心）。
 * <p>
 * 为什么不用 ChatClient 的内置工具循环：框架循环给不了三样东西——
 * <ul>
 *   <li><b>轮次上限的优雅终止</b>：内置循环超限是抛异常，我们要的是
 *       "已分析 N 轮仍未完成，建议简化问题" 这种对用户有意义的收尾；</li>
 *   <li><b>每轮的中断判断</b>：SQL 反复报错时可以在回灌时带更强的指引；</li>
 *   <li><b>执行轨迹</b>：每轮工具调用要落 trace 推 SSE。</li>
 * </ul>
 * 这个循环就是 Agent 的全部本质：模型说想用什么工具、参数是什么，
 * 我们执行、把结果塞回对话、再问模型下一步。所有 Agent 框架
 * （LangGraph、LangChain）都只是这个循环的包装。
 * <p>
 * 关键实现细节：给模型的 ChatOptions 里<b>只带工具定义（FunctionTool）
 * 不带 ToolCallback 执行器</b>。这样 ChatModel 内部的 ToolCallingManager
 * 看到没有可执行的回调，不会自动执行循环，而是把带 tool_calls 的响应
 * 原样返回给我们——循环的控制权就拿到了手里。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class AgentService {

    private final ChatModel chatModel;
    private final SchemaLinkingService schemaLinkingService;
    private final List<ToolCallback> toolCallbacks;
    private final Map<String, ToolCallback> toolCallbackMap;
    private final SemanticPromptBuilder promptBuilder;
    private final SemanticModel model;
    private final SessionService sessionService;
    private final ConfigService configService;
    private final AuditService auditService;
    private final AgentProperties properties;

    /** 问答专用线程池（舱壁隔离）：拒绝语义是"告知用户"，不是静默丢弃 */
    private final ThreadPoolExecutor pipeline;

    public AgentService(ChatModel chatModel,
                        SchemaLinkingService schemaLinkingService,
                        ReportTools reportTools,
                        SemanticPromptBuilder promptBuilder,
                        SemanticModel model,
                        SessionService sessionService,
                        ConfigService configService,
                        AuditService auditService,
                        AgentProperties properties) {
        this.chatModel = chatModel;
        this.schemaLinkingService = schemaLinkingService;
        this.toolCallbacks = List.of(MethodToolCallbackProvider.builder()
                .toolObjects(reportTools)
                .build()
                .getToolCallbacks());
        Map<String, ToolCallback> map = new LinkedHashMap<>();
        this.toolCallbacks.forEach(cb -> map.put(cb.getToolDefinition().name(), cb));
        this.toolCallbackMap = Map.copyOf(map);
        this.promptBuilder = promptBuilder;
        this.model = model;
        this.sessionService = sessionService;
        this.configService = configService;
        this.auditService = auditService;
        this.properties = properties;

        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        AtomicInteger seq = new AtomicInteger();
        this.pipeline = new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                r -> new Thread(r, "agent-pipeline-" + seq.getAndIncrement()),
                new ThreadPoolExecutor.AbortPolicy());
        this.pipeline.allowCoreThreadTimeOut(true);
    }

    @PreDestroy
    public void shutdown() {
        pipeline.shutdown();
    }

    public void chat(String sessionId, String userId, String question, SseEmitter emitter) {
        try {
            pipeline.execute(() -> run(sessionId, userId, question, emitter));
        } catch (RejectedExecutionException e) {
            // fail-loud：排队满了要告诉用户，不能静默丢弃一个有人在等的请求
            log.warn("[FAIL-LOUD] 问答队列已满，拒绝请求 sessionId={} 队列={}", sessionId, pipeline.getQueue().size());
            SseSender.send(emitter, "error",
                    "系统繁忙（当前排队 " + pipeline.getQueue().size() + " 个请求），请稍后重试", sessionId);
            SseSender.complete(emitter);
        }
    }

    private void run(String sessionId, String userId, String question, SseEmitter emitter) {
        long start = System.currentTimeMillis();
        sessionService.appendMessage(sessionId, "user", question, null);

        AgentRunContext ctx = new AgentRunContext(sessionId, userId, question);
        if (configService.getBoolean("agent.showTrace")) {
            ctx.setStepListener(step -> SseSender.sendJson(emitter, "step", step.toEvent(), sessionId));
        }

        SseSender.send(emitter, "stage", "正在理解问题…", sessionId);

        String answer;
        String error = null;
        try {
            ctx.nextRound();
            answer = runReAct(ctx, question);
        } catch (Exception e) {
            log.error("[Agent] 执行失败 sessionId={}", sessionId, e);
            error = rootMessage(e);
            answer = "查询失败：" + error;
            SseSender.send(emitter, "error", answer, sessionId);
        }

        emitResult(emitter, sessionId, ctx, answer);

        long elapsed = System.currentTimeMillis() - start;
        String messageId = sessionService.appendMessage(sessionId, "assistant", answer, buildPayload(ctx));
        auditService.record(ctx, messageId, error, elapsed);

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("messageId", messageId);
        done.put("route", ctx.getRoute());
        done.put("steps", ctx.getSteps().size());
        done.put("elapsedMs", elapsed);
        SseSender.sendJson(emitter, "done", done, sessionId);
        SseSender.complete(emitter);
    }

    // ------------------------------------------------------------
    // 手写 ReAct 循环
    // ------------------------------------------------------------

    private String runReAct(AgentRunContext ctx, String question) {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(buildSystemPrompt(question)));
        history.add(new UserMessage(question));

        int maxSteps = configService.getInt("agent.maxSteps", properties.getAgent().getMaxSteps());

        while (ctx.currentRound() <= maxSteps) {
            ctx.nextRound(); // 每轮递增，否则 currentRound() 恒为 1，轮次上限形同虚设
            ChatResponse response = chatModel.call(new Prompt(history, baseOptions()));
            AssistantMessage output = response.getResult().getOutput();

            if (output == null || !output.hasToolCalls()) {
                // 模型决定不再调用工具：这就是最终回答
                return output == null ? "" : output.getText();
            }

            // 执行本轮的所有工具调用
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : output.getToolCalls()) {
                toolResponses.add(executeTool(call, ctx));
            }

            // 把模型的工具调用请求和我们的执行结果都放回对话，模型才能"看到"结果继续推理
            history.add(output);
            history.add(ToolResponseMessage.builder().responses(toolResponses).build());
        }

        // 轮次上限：优雅终止而不是死循环。这里刻意不抛异常——用户需要的是
        // 一句能指导下一步的话，而不是一个错误码。
        log.warn("[FAIL-LOUD] 达到最大轮次 {} 仍未完成，sessionId={} 问题={}", maxSteps, ctx.getSessionId(), ctx.getQuestion());
        return "已分析 " + maxSteps + " 轮仍未得出最终结论，为控制成本已停止。\n"
                + "建议：把问题拆小（例如限定到单张表或单个指标），或者换个更明确的问法。";
    }

    /** 执行单个工具调用，异常兜底为工具消息而非中断整轮 */
    private ToolResponseMessage.ToolResponse executeTool(AssistantMessage.ToolCall call, AgentRunContext ctx) {
        ToolCallback callback = toolCallbackMap.get(call.name());
        if (callback == null) {
            String msg = "工具 " + call.name() + " 不存在。可用工具：" + toolCallbackMap.keySet();
            return new ToolResponseMessage.ToolResponse(call.id(), call.name(), msg);
        }
        try {
            // 权限与身份走 ToolContext：Spring AI 保证它不会发给模型，
            // 因此不会被 prompt 注入读取或篡改
            String result = callback.call(call.arguments(), new ToolContext(ctx.asToolContextMap()));
            return new ToolResponseMessage.ToolResponse(call.id(), call.name(), result);
        } catch (Exception e) {
            log.error("[Agent] 工具执行异常 tool={}", call.name(), e);
            String msg = "工具 " + call.name() + " 执行异常：" + rootMessage(e)
                    + "。请检查参数是否正确后重试，或换一个工具。";
            return new ToolResponseMessage.ToolResponse(call.id(), call.name(), msg);
        }
    }

    /**
     * 只带工具定义、不带执行器。
     * 这样 ChatModel 内部不会自动执行工具循环，tool_calls 会原样返回给我们。
     */
    private ToolCallingChatOptions baseOptions() {
        List<OpenAiApi.FunctionTool> tools = toolCallbacks.stream()
                .map(cb -> {
                    ToolDefinition def = cb.getToolDefinition();
                    return new OpenAiApi.FunctionTool(new OpenAiApi.FunctionTool.Function(
                            def.name(),
                            def.description(),
                            def.inputSchema()));
                })
                .toList();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .temperature(configService.getDouble("chat.temperature", 0.1))
                .tools(tools)
                .build();
        // 关闭思考模式：qwen3.7-flash 大 prompt + 思考 + 生成 SQL 会超过网关
        // 约 90 秒的响应超时（实测非流式请求 91 秒被网关断开 EOF）。
        // 报表场景的确定性需求下，关思考反而更快更稳。
        options.setExtraBody(Map.of("enable_thinking", false));
        return options;
    }

    // ------------------------------------------------------------
    // 产物输出与持久化
    // ------------------------------------------------------------

    private void emitResult(SseEmitter emitter, String sessionId, AgentRunContext ctx, String answer) {
        QueryResult last = ctx.lastResult();
        if (last != null) {
            SseSender.send(emitter, "sql", last.getSql(), sessionId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("columns", last.getColumns());
            data.put("rows", last.getRows());
            data.put("rowCount", last.rowCount());
            data.put("truncated", last.isTruncated());
            SseSender.sendJson(emitter, "data", data, sessionId);
            if (last.isTruncated()) {
                SseSender.send(emitter, "warn",
                        "结果已达行数上限被截断，展示的不是全部数据", sessionId);
            }
        }
        if (ctx.getClarification() != null) {
            SseSender.send(emitter, "clarify", answer, sessionId);
        } else if (answer != null && !answer.isBlank()) {
            SseSender.send(emitter, "token", answer, sessionId);
        }
    }

    private String buildPayload(AgentRunContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("route", ctx.getRoute());
        payload.put("templateId", ctx.getTemplateId());
        payload.put("steps", ctx.getSteps().stream().map(AgentStep::toEvent).toList());
        QueryResult last = ctx.lastResult();
        if (last != null) {
            payload.put("sql", last.getSql());
            payload.put("columns", last.getColumns());
            payload.put("rows", last.getRows());
        }
        return JSON.toJSONString(payload);
    }

    /**
     * 系统提示（每次提问动态构建）。
     * <p>
     * 当前日期必须显式给出——模型不知道今天几号，而"上个月""最近半年"
     * 这类相对时间在报表提问里占比极高，这是最常见的错误来源之一。
     * <p>
     * 动态注入 Schema Linking 的结果：相关表结构 + 相关指标口径 + golden 示例。
     * 模型写 SQL 前不需要先探索一遍表——相关的列名、枚举映射、口径边界
     * 已经摆在面前了，这是 NL2SQL 准确率的主要来源。
     */
    private String buildSystemPrompt(String question) {
        String custom = configService.get("agent.systemPrompt");
        if (custom != null && !custom.isBlank()) {
            return custom.replace("{{today}}", LocalDate.now().toString());
        }

        SchemaLinkingService.LinkingResult linked = schemaLinkingService.link(question);

        return """
                你是企业报表数据助手，负责把用户的自然语言问题转成数据查询并解读结果。

                今天是 %s。用户说的"上个月""最近半年""今年"等相对时间，你要据此换算成
                具体日期再传给工具，不要把中文时间词原样传入。

                ## 与本次问题相关的表结构（优先使用，列名和枚举值以此为准）
                %s
                ## 相关指标口径（涉及这些指标时，口径边界必须照抄）
                %s
                ## 参考示例（模仿其写法：join 路径、口径过滤、枚举值）
                %s
                ## 全部可用的表（概览，需要更多结构时用 get_table_schema 查看）
                %s
                ## 可用的报表模板
                %s
                ## 工作流程
                1. 先判断问题能否匹配上面某个报表模板。能匹配就用 run_report_template，
                   这条路的口径是人工验证过的，准确率最高。
                2. 匹配不上时：优先使用上面已给出的相关表结构写 SQL 并调用 execute_sql；
                   如果需要的表不在上面，先 get_table_schema 补充查看；涉及指标再 list_metrics 确认口径。
                3. 遇到真实的业务口径歧义（比如销售额含不含退款），用 ask_clarification 反问，
                   不要替用户做决定。
                4. 拿到数据后，用一段简洁的中文解读结论：说清关键数字、变化趋势、值得注意的异常。

                ## 硬性要求
                - 绝不编造数字。所有数字必须来自工具返回的真实查询结果。
                - 没有调用任何查询工具就不要给出数字结论。
                - 工具返回错误时，读懂错误信息和修复建议后修改重试，不要重复提交同样的内容。
                - 结果为 0 行时如实说明"没有符合条件的数据"，不要编造或含糊带过。
                - 解读数据时不要重复罗列整张表格，前端会单独渲染表格和图表，你只需要给结论。
                """.formatted(
                LocalDate.now(),
                promptBuilder.renderTables(linked.tables()),
                promptBuilder.renderMetrics(linked.metricNames()),
                promptBuilder.renderGoldenExamples(linked.goldenExamples()),
                promptBuilder.renderTableCatalog(),
                promptBuilder.renderTemplateCatalog());
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
