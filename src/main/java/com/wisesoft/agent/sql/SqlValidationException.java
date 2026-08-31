package com.wisesoft.agent.sql;

import lombok.Getter;

import java.util.List;

/**
 * SQL 执行/校验失败的结构化异常。
 * <p>
 * 这个类的存在是为了服务「错误自修正」：BIRD 官方把自我纠错的失败明确归因为
 * self-enhancement bias —— 模型倾向认为自己写的是对的。所以让它"再检查一遍"
 * 收效有限，必须给它**外部信号**。
 * <p>
 * 因此这里刻意不只带一句报错，还带上 {@link #hints}：具体该怎么改。
 * 「表 dim_product 不在白名单」远不如「订单表不能直连商品表，请经由
 * fact_order_item 中转」有用。
 *
 * @author yuanke
 */
@Getter
public class SqlValidationException extends RuntimeException {

    public enum Stage {
        /** 语法解析失败 */
        PARSE,
        /** 白名单/语义层校验失败 */
        GUARD,
        /** EXPLAIN 干跑失败 */
        DRY_RUN,
        /** 实际执行失败 */
        EXECUTE
    }

    private final Stage stage;

    /** 修复建议，逐条回灌给模型 */
    private final List<String> hints;

    public SqlValidationException(Stage stage, String message, List<String> hints) {
        super(message);
        this.stage = stage;
        this.hints = hints == null ? List.of() : List.copyOf(hints);
    }

    public SqlValidationException(Stage stage, String message) {
        this(stage, message, List.of());
    }

    /**
     * 渲染成回灌给模型的文本。格式固定，让模型每次看到的结构一致。
     */
    public String toModelText() {
        StringBuilder sb = new StringBuilder();
        sb.append("SQL 在【").append(stageName()).append("】阶段被拒绝：").append(getMessage());
        if (!hints.isEmpty()) {
            sb.append("\n修复建议：");
            for (String h : hints) {
                sb.append("\n  - ").append(h);
            }
        }
        sb.append("\n请据此修改 SQL 后重新调用。不要重复提交同一条 SQL。");
        return sb.toString();
    }

    private String stageName() {
        return switch (stage) {
            case PARSE -> "语法解析";
            case GUARD -> "安全校验";
            case DRY_RUN -> "执行计划检查";
            case EXECUTE -> "执行";
        };
    }
}
