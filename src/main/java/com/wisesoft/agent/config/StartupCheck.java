package com.wisesoft.agent.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 启动期自检。
 * <p>
 * Spring AI 1.1.8 在 bean 创建阶段就强校验 api-key 非空（OpenAIAutoConfigurationUtil
 * 里一个 Assert.hasText），空 key 会直接导致启动失败。为了让本地开发能在没有真实
 * key 的情况下把其余链路跑起来，local profile 里给了一个占位值——但占位值必须
 * 大声说出来，否则第一次调用模型时报 401，排查方向会被带偏。
 * <p>
 * 这里沿用 dtbd-ai-service 的 fail-loud 约定：降级不静默。
 *
 * @author yuanke
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class StartupCheck {

    /** 与 application-local.yml 中的占位值保持一致 */
    public static final String PLACEHOLDER_KEY = "sk-placeholder-not-a-real-key";

    private final AgentProperties properties;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.options.model:}")
    private String model;

    @PostConstruct
    public void check() {
        if (PLACEHOLDER_KEY.equals(apiKey)) {
            log.warn("[FAIL-LOUD] 当前使用的是占位 API Key，任何模型调用都会返回鉴权失败。"
                    + "请设置环境变量 AI_API_KEY，或在 application-local.yml 中填入真实密钥。");
        }
        log.info("[启动自检] 模型={}, 业务库={}, 只读账号={}, SQL上限={}行/{}秒",
                model,
                properties.getBusiness().getSchema(),
                properties.getBusiness().getUsername(),
                properties.getSqlExec().getMaxRows(),
                properties.getSqlExec().getTimeoutSeconds());
    }
}
