package com.wisesoft.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 应用配置（前缀 report-agent）
 * <p>
 * 这里放的是启动期就要定下来、改了必须重启的东西。运行期可调的参数
 * （模型名、温度、提示词、各种阈值）走 {@link com.wisesoft.agent.service.ConfigService}
 * 的 DB 配置中心，保存即生效。
 *
 * @author yuanke
 */
@Data
@Component
@ConfigurationProperties(prefix = "report-agent")
public class AgentProperties {

    /** 内部鉴权 token，必须由环境变量 AGENT_TRUSTED_TOKEN 提供，缺失拒绝启动 */
    private String trustedToken;

    private Business business = new Business();
    private SqlExec sqlExec = new SqlExec();
    private Agent agent = new Agent();
    private Context context = new Context();
    private RateLimit ratelimit = new RateLimit();

    /** 业务库（被查询的那个库）连接信息，与智能体自身的元数据库分开 */
    @Data
    public static class Business {
        private String url;
        /**
         * 只读账号。这是 SqlGuard 之外的第二道防线：即使 AST 校验被绕过，
         * 数据库层面也没有任何写权限。生产环境绝不能配成有写权限的账号。
         */
        private String username;
        private String password;
        private String schema;
        private int maxPoolSize = 5;
    }

    /** SQL 执行硬限制 */
    @Data
    public static class SqlExec {
        /** 单次查询最大返回行数，SqlGuard 会强制注入 LIMIT */
        private int maxRows = 1000;
        /** 单次查询超时（秒），超时由 JDBC 层中断 */
        private int timeoutSeconds = 20;
        /** 是否要求执行前先跑 EXPLAIN 干跑校验 */
        private boolean explainDryRun = true;
    }

    /** Agent 循环控制 */
    @Data
    public static class Agent {
        /**
         * ReAct 最大轮次。超过即优雅终止并如实告知用户，不再继续烧 token。
         * 没有这个上限，一个模型反复改不对的 SQL 能把预算跑光。
         */
        private int maxSteps = 8;
        /** SQL 出错后的最大自修正次数 */
        private int maxSqlRepairs = 2;
        /** 单次问答整体超时（毫秒） */
        private long timeoutMs = 120000;
        /** SSE 超时（毫秒） */
        private long sseTimeoutMs = 300000;
    }

    /** 上下文预算（表结构 DDL 很占地方，报表场景比 RAG 更需要这个） */
    @Data
    public static class Context {
        /** 模型窗口映射，形如 qwen3=131072,default=32768 */
        private String modelWindows = "qwen3=131072,qwen-plus=131072,qwen-max=32768,default=32768";
        private int defaultWindowTokens = 32768;
        private double safetyFactor = 0.7;
        /** 成本软上限（token，0=不限） */
        private int costCapTokens = 12000;
        private int maxOutputTokens = 2000;
        private int historyMaxTokens = 1200;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private int chatPerMinute = 10;
        private int queryPerMinute = 30;
    }
}
