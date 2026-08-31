package com.wisesoft.agent.support;

import com.wisesoft.agent.agent.AgentRunContext;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 测试脚手架：构造工具执行所需的运行上下文。
 *
 * @author yuanke
 */
public final class SemanticTestSupport {

    private SemanticTestSupport() {
    }

    /** 运行上下文与它对应的 ToolContext，成对返回便于断言副作用 */
    public record RunContextFixture(AgentRunContext context, ToolContext toolContext) {
    }

    public static RunContextFixture newContext() {
        return newContext("测试问题");
    }

    public static RunContextFixture newContext(String question) {
        AgentRunContext ctx = new AgentRunContext("test-session", "tester", question);
        return new RunContextFixture(ctx, new ToolContext(ctx.asToolContextMap()));
    }
}
