package com.wisesoft.agent.agent;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 执行轨迹中的一步。
 * <p>
 * 报表智能体必须让用户看见"选了哪些表、生成了什么 SQL、校验过没有"，
 * 否则一个数字扔出来没人敢信。这些步骤会通过 SSE 的 step 事件实时推给前端，
 * 同时落进消息 payload，恢复会话时能原样重现。
 *
 * @author yuanke
 */
@Data
public class AgentStep {

    public enum Status {
        RUNNING, SUCCESS, FAILED
    }

    /** 第几轮（ReAct 循环的轮次，从 1 开始） */
    private int round;

    /** 工具名，或 think / answer 这类非工具动作 */
    private String action;

    /** 给用户看的一句话描述 */
    private String label;

    private Status status = Status.RUNNING;

    /** 输入摘要（已脱敏，不含权限上下文） */
    private String input;

    /** 输出摘要，长内容会截断 */
    private String output;

    /** 失败原因 */
    private String error;

    private long elapsedMs;

    private final long startedAt = System.currentTimeMillis();

    private static final int SUMMARY_MAX = 500;

    public static AgentStep start(int round, String action, String label, String input) {
        AgentStep s = new AgentStep();
        s.round = round;
        s.action = action;
        s.label = label;
        s.input = truncate(input);
        return s;
    }

    public AgentStep succeed(String output) {
        this.status = Status.SUCCESS;
        this.output = truncate(output);
        this.elapsedMs = System.currentTimeMillis() - startedAt;
        return this;
    }

    public AgentStep fail(String error) {
        this.status = Status.FAILED;
        this.error = truncate(error);
        this.elapsedMs = System.currentTimeMillis() - startedAt;
        return this;
    }

    /** 推给前端的精简形态 */
    public Map<String, Object> toEvent() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("round", round);
        m.put("action", action);
        m.put("label", label);
        m.put("status", status.name().toLowerCase());
        m.put("elapsedMs", elapsedMs);
        if (error != null) {
            m.put("error", error);
        }
        return m;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > SUMMARY_MAX ? one.substring(0, SUMMARY_MAX) + "…" : one;
    }
}
