package com.wisesoft.agent.agent;

import com.wisesoft.agent.sql.QueryResult;
import lombok.Getter;
import lombok.Setter;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 单次问答的运行上下文。
 * <p>
 * 它经由 Spring AI 的 {@link ToolContext} 传给工具。这个选择很关键：
 * Spring AI 明确保证 ToolContext 的内容<b>不会发送给模型</b>，所以
 * 用户身份、租户、数据可见范围这些东西放在这里既能被工具用到，
 * 又不会泄进 prompt，也不会被 prompt 注入篡改。
 * <p>
 * 同时它承担产物收集：工具执行过程中产生的轨迹、SQL、结果集都写进来，
 * 由外层统一推 SSE 和落库。工具本身只返回给模型看的文本。
 *
 * @author yuanke
 */
@Getter
public class AgentRunContext {

    /** ToolContext 里的 key */
    public static final String KEY = "agentRunContext";

    private final String sessionId;
    private final String userId;
    private final String question;

    private final List<AgentStep> steps = new ArrayList<>();
    private final AtomicInteger round = new AtomicInteger(0);

    /** 本轮执行过的查询，按发生顺序 */
    private final List<QueryResult> results = new ArrayList<>();

    /** 命中的模板 id，走 NL2SQL 时为 null */
    @Setter
    private String templateId;

    /** 路由结果：template / nl2sql / clarify */
    @Setter
    private String route;

    /** SQL 自修正次数 */
    private int repairCount;

    /** 反问内容，非空表示本轮以澄清结束而不是给答案 */
    @Setter
    private String clarification;

    /** 步骤产生时的实时回调，用于推 SSE */
    @Setter
    private Consumer<AgentStep> stepListener;

    public AgentRunContext(String sessionId, String userId, String question) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.question = question;
    }

    /** 从 ToolContext 取回本上下文。取不到说明调用链没串对，属于编码错误。 */
    public static AgentRunContext from(ToolContext toolContext) {
        if (toolContext == null) {
            throw new IllegalStateException("ToolContext 缺失：工具必须在 Agent 运行上下文中调用");
        }
        Object ctx = toolContext.getContext().get(KEY);
        if (!(ctx instanceof AgentRunContext c)) {
            throw new IllegalStateException("ToolContext 中没有 " + KEY + "：调用链未正确传递运行上下文");
        }
        return c;
    }

    public Map<String, Object> asToolContextMap() {
        return Map.of(KEY, this);
    }

    public int nextRound() {
        return round.incrementAndGet();
    }

    public int currentRound() {
        return Math.max(round.get(), 1);
    }

    public AgentStep beginStep(String action, String label, String input) {
        AgentStep step = AgentStep.start(currentRound(), action, label, input);
        steps.add(step);
        notifyListener(step);
        return step;
    }

    /** 步骤状态变化后调用，触发一次 SSE 推送 */
    public void endStep(AgentStep step) {
        notifyListener(step);
    }

    private void notifyListener(AgentStep step) {
        if (stepListener != null) {
            stepListener.accept(step);
        }
    }

    public void recordResult(QueryResult r) {
        results.add(r);
    }

    public void incrementRepair() {
        repairCount++;
    }

    /** 最后一次查询结果，前端渲染表格与图表用 */
    public QueryResult lastResult() {
        return results.isEmpty() ? null : results.get(results.size() - 1);
    }

    public boolean hasResult() {
        return !results.isEmpty();
    }
}
