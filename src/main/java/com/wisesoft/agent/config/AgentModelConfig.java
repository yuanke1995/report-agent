package com.wisesoft.agent.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;

import java.util.List;

/**
 * 手写 ReAct 循环专用的 ChatModel。
 * <p>
 * Spring AI 的 ChatModel 收到带 tool_calls 的响应后会尝试自动执行工具，
 * 这会让循环控制权落在框架手里。这里用 {@code ToolExecutionEligibilityPredicate}
 * 显式关掉自动执行——模型永远只返回"想调用什么工具、参数是什么"的意图，
 * 真正执行和循环由 {@link com.wisesoft.agent.service.AgentService} 控制。
 * 这样轮次上限、SQL 修复上限、执行轨迹这些框架循环给不了的能力
 * 才能落在自己手里。
 * <p>
 * 注意：OpenAiChatOptions 的自动配置 bean 并不总是暴露，这里直接
 * 从配置属性构建，避免依赖装配细节。
 *
 * @author yuanke
 */
@Configuration
public class AgentModelConfig {

    @Bean
    @Primary
    public ChatModel reactChatModel(OpenAiApi openAiApi,
                                    io.micrometer.observation.ObservationRegistry observationRegistry,
                                    @Value("${spring.ai.openai.chat.options.model:}") String model,
                                    @Value("${spring.ai.openai.chat.options.temperature:0.1}") double temperature) {
        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        ToolCallingManager toolCallingManager = new DefaultToolCallingManager(
                observationRegistry,
                // 模型侧解析器为空：配合 predicate=false，模型永远不执行工具
                new StaticToolCallbackResolver(List.of()),
                new DefaultToolExecutionExceptionProcessor(true));

        return new OpenAiChatModel(
                openAiApi,
                defaultOptions,
                toolCallingManager,
                // 不做重试：请求失败由 Agent 层整体兜底，避免工具调用被重放
                RetryTemplate.builder().maxAttempts(1).build(),
                observationRegistry,
                // 关键：永远返回 false，工具执行循环完全交给 AgentService
                (options, response) -> false);
    }
}
