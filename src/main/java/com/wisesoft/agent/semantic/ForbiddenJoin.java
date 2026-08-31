package com.wisesoft.agent.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 明确禁止的连接（对应 joins.yml 的 forbiddenJoins）
 * <p>
 * 白名单已经能挡住未登记的连接，这份黑名单额外提供的是**可解释性**：
 * 命中时能告诉模型"为什么不行、应该怎么走"，而不是干巴巴一句"未批准"。
 * 结构化的错误提示配上修复建议，模型重试一次的成功率明显更高。
 *
 * @author yuanke
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class ForbiddenJoin {

    private String left;

    private String right;

    /** 若该表对只允许经由某条特定路径连接，填 joins.yml 的 id */
    private String viaOnly;

    /** 禁止原因，会原样回灌给模型作为修复提示 */
    private String reason;
}
